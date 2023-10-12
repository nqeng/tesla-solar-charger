(ns tesla-solar-charger.core
  (:gen-class)
  (:require
   [clj-http.client :as client]
   [tesla-solar-charger.gophers.provide-car-state :refer [provide-new-car-state provide-current-car-state]]
   [tesla-solar-charger.gophers.provide-site-data :refer [provide-new-site-data provide-current-site-data]]
   [tesla-solar-charger.gophers.regulate-charge-rate :refer [regulate-car-charge-rate]]
   [tesla-solar-charger.gophers.logger :refer [log-loop log]]
   [cheshire.core :as json]
   [tesla-solar-charger.implementations.dummy-tesla :as dummy-tesla]
   [tesla-solar-charger.implementations.tesla :as tesla]
   [tesla-solar-charger.implementations.sungrow-site :as sungrow-site]
   [tesla-solar-charger.interfaces.regulator :as regulator]
   [tesla-solar-charger.implementations.simple-regulation-creater :as simple-regulation-creater]
   [tesla-solar-charger.implementations.target-time-regulation-creater :as target-time-regulation-creater]
   [tesla-solar-charger.implementations.default-regulator :as default-regulator]
   [tesla-solar-charger.time-utils :as time-utils]
   [clojure.core.async :as async]))

(defn provide-settings
  [log-prefix settings-filename get-chan set-chan error-chan log-chan]
  (async/go
    (try
      (loop [settings {}]
        (let [[value chan] (async/alts! [[get-chan settings] set-chan])]
          (if (= get-chan chan)
            (let [success value]
              (when (false? success)
                (throw (ex-info "Channel closed!" {})))
              (async/>! log-chan {:level :info :prefix log-prefix :message "Provided current settings"})
              (recur settings))
            (let [set-request value]
              (when (nil? set-request)
                (throw (ex-info "Channel closed!" {})))
              (let [new-settings (set-request settings)]
                (async/>! log-chan {:level :info :prefix log-prefix :message "Received change request"})
                (async/>! log-chan {:level :verbose :prefix log-prefix :message settings})
                (recur new-settings))))))
      (catch clojure.lang.ExceptionInfo e
        (async/>! error-chan e))
      (catch Exception e
        (async/>! error-chan e)))))

(defn receive-sms
  [log-prefix clicksend-username clicksend-api-key output-chan error-chan log-chan]
  (async/go
    (try
      (client/put
       "https://rest.clicksend.com/v3/sms/inbound-read"
       {:basic-auth [clicksend-username clicksend-api-key]})
      (loop []
        (async/>! log-chan {:level :info :prefix log-prefix :message "Retrieving text messages..."})
        (let [sms-messages (-> (client/get
                                "https://rest.clicksend.com/v3/sms/inbound"
                                {:basic-auth [clicksend-username clicksend-api-key]})
                               :body
                               json/parse-string
                               (get "data")
                               (get "data"))]
          (async/>! log-chan {:level :info :prefix log-prefix :message "Got text messages"})
          (async/>! log-chan {:level :verbose :prefix log-prefix :message sms-messages})
          (doseq [sms sms-messages]
            (client/put
             (str "https://rest.clicksend.com/v3/sms/inbound-read/" (get sms "message_id"))
             {:basic-auth [clicksend-username clicksend-api-key]})
            (when
             (and (some? sms)
                  (false? (async/>! output-chan sms)))
              (throw (ex-info "Channel closed" {}))))
          (when-let [next-state-available-time (time-utils/time-after-seconds 2)]
            (when (.isAfter next-state-available-time (java.time.LocalDateTime/now))
              (async/>! log-chan {:level :info
                                  :prefix log-prefix
                                  :message (format "Sleeping until %s"
                                                   (time-utils/format-time "yyyy-MM-dd HH:mm:ss" next-state-available-time))})
              (Thread/sleep (time-utils/millis-between-times (java.time.LocalDateTime/now) next-state-available-time))))
          (recur)))
      (catch clojure.lang.ExceptionInfo e
        (async/>! error-chan e))
      (catch Exception e
        (async/>! error-chan e)))))

(defn process-sms
  [log-prefix sms-chan settings-chan error-chan log-chan]
  (async/go
    (try
      (loop []
        (async/>! log-chan {:level :info :prefix log-prefix :message "Waiting..."})
        (let [sms (async/<! sms-chan)]
          (when (nil? sms)
            (throw (ex-info "Channel closed" {})))
          (async/>! log-chan {:level :info :prefix log-prefix :message "Got text message"})
          (async/>! log-chan {:level :verbose :prefix log-prefix :message sms})
          (let [body (get sms "body")
                match (re-find #"(\d+)%.+(\d\d):(\d\d)" body)
                target-percent (Integer/parseInt (get match 1))
                target-hour (Integer/parseInt (get match 2))
                target-minute (Integer/parseInt (get match 3))
                target-time (-> (java.time.LocalDateTime/now)
                                (.withHour target-hour)
                                (.withMinute target-minute))
                settings-action (fn [settings]
                                  (-> settings
                                      (assoc :target-time target-time)
                                      (assoc :target-percent target-percent)))]
            (async/>! log-chan {:level :info :prefix log-prefix :message (format "Sent %s to settings" settings-action)})
            (when (and (some? settings-action)
                       (false? (async/>! settings-chan settings-action)))
              (throw (ex-info "Channel closed" {}))))
          (recur)))
      (catch clojure.lang.ExceptionInfo e
        (async/>! error-chan e))
      (catch Exception e
        (async/>! error-chan e)))))

(def solar-sites [])

(defn -main
  [& args]
  (println "Starting...")
  (let [car (dummy-tesla/->DummyTesla "1234")
        site1-regulator (-> (default-regulator/->DefaultRegulator car (first solar-sites))
                            (regulator/with-regulation-creater (simple-regulation-creater/->SimpleRegulationCreater 1000 8 8)))
        site2-regulator (-> (default-regulator/->DefaultRegulator car (second solar-sites))
                            (regulator/with-regulation-creater (simple-regulation-creater/->SimpleRegulationCreater 1000 8 8)))
        error-chan (async/chan (async/dropping-buffer 1))
        new-car-state-chan (async/chan (async/sliding-buffer 1))
        current-car-state-chan (async/chan)
        new-site1-data-chan (async/chan (async/sliding-buffer 1))
        current-site1-data-chan (async/chan)
        new-site2-data-chan (async/chan (async/sliding-buffer 1))
        current-site2-data-chan (async/chan)
        log-chan (async/chan 10)]

    (provide-new-car-state "New state (Tesla)" car new-car-state-chan error-chan log-chan)

    (provide-current-car-state "Current state (Tesla)" current-car-state-chan new-car-state-chan error-chan log-chan)

    (provide-new-site-data "New data (NQE)" (first solar-sites) new-site1-data-chan error-chan log-chan)

    (provide-current-site-data "Current data (NQE)" current-site1-data-chan new-site1-data-chan error-chan log-chan)

    (provide-new-site-data "New data (Summerfield)" (second solar-sites) new-site2-data-chan error-chan log-chan)

    (provide-current-site-data "Current data (Summerfield)" current-site2-data-chan new-site2-data-chan error-chan log-chan)

    (regulate-car-charge-rate "Regulator (NQE)" site1-regulator current-car-state-chan current-site1-data-chan error-chan log-chan)

    (regulate-car-charge-rate "Regulator (Summerfield)" site2-regulator current-car-state-chan current-site2-data-chan error-chan log-chan)

    (async/go
      (log-loop
       :verbose
       log-chan
       error-chan))

    (.addShutdownHook
     (Runtime/getRuntime)
     (Thread.
      (fn []
        (log :info nil "Closing channels...")
        (async/close! new-car-state-chan)
        (async/close! new-site1-data-chan)
        (async/close! current-car-state-chan)
        (async/close! current-site1-data-chan)
        (async/close! error-chan)
        (async/close! log-chan)
        (log :info nil "Channels closed."))))

    (let [e (async/<!! error-chan)]
      (when (not (nil? e))
        (let [stack-trace-string (with-out-str (clojure.stacktrace/print-stack-trace e))]
          (async/>!! log-chan {:level :error :message stack-trace-string})
          (try (client/post "https://ntfy.sh/github-nqeng-tesla-solar-charger" {:body stack-trace-string})))))))

