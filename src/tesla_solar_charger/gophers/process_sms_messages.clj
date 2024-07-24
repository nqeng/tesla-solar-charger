(ns tesla-solar-charger.gophers.process-sms-messages
  (:require
   [cheshire.core :as json]
   [tesla-solar-charger.utils :as utils]
   [tesla-solar-charger.interfaces.sms :as sms]
   [clojure.core.async :as async]
   [clj-http.client :as client]))

(defn get-sms-messages
  [clicksend-username clicksend-api-key]
  (try
    (-> (client/get
         "https://rest.clicksend.com/v3/sms/inbound"
         {:basic-auth [clicksend-username clicksend-api-key]})
        :body
        json/parse-string
        (get "data")
        (get "data"))
    (catch java.net.UnknownHostException e
      (let [error (.getMessage e)]
        (throw (ex-info
                (str "Network error; " error)
                {:type :network-error}))))
    (catch java.net.NoRouteToHostException e
      (let [error (.getMessage e)]
        (throw (ex-info
                (str "Network error; " error)
                {:type :network-error}))))))

(defn mark-all-as-read
  [clicksend-username clicksend-api-key]
  (try
    (client/put
     "https://rest.clicksend.com/v3/sms/inbound-read"
     {:basic-auth [clicksend-username clicksend-api-key]})
    (catch java.net.UnknownHostException e
      (let [error (.getMessage e)]
        (throw (ex-info
                (str "Network error; " error)
                {:type :network-error}))))
    (catch java.net.NoRouteToHostException e
      (let [error (.getMessage e)]
        (throw (ex-info
                (str "Network error; " error)
                {:type :network-error}))))))

(defn mark-as-read
  [clicksend-username clicksend-api-key sms-message]
  (try
    (client/put
     (str "https://rest.clicksend.com/v3/sms/inbound-read/" (get sms-message "message_id"))
     {:basic-auth [clicksend-username clicksend-api-key]})
    (catch java.net.UnknownHostException e
      (let [error (.getMessage e)]
        (throw (ex-info
                (str "Network error; " error)
                {:type :network-error}))))
    (catch java.net.NoRouteToHostException e
      (let [error (.getMessage e)]
        (throw (ex-info
                (str "Network error; " error)
                {:type :network-error}))))))

(defn process-sms-messages
  [log-prefix clicksend-username clicksend-api-key message-processors error-chan log-chan]
  (async/go
    (try
      (mark-all-as-read clicksend-username clicksend-api-key)
      (loop []
        (async/>! log-chan {:level :info :prefix log-prefix :message "Getting text messages..."})
        (let [sms-messages (get-sms-messages clicksend-username clicksend-api-key)]
          (async/>! log-chan {:level :info :prefix log-prefix :message (format "Got %s text messages" (count sms-messages))})
          (when (not (nil? (seq sms-messages)))
            (async/>! log-chan {:level :verbose :prefix log-prefix :message sms-messages}))
          (doseq [sms sms-messages]
            (mark-as-read clicksend-username clicksend-api-key sms)
            (doseq [processor message-processors
                    :let [result (sms/process-sms processor sms)
                          _ (async/>! log-chan {:level :verbose
                                                :prefix log-prefix
                                                :message (format "Processor %s returned %s" (get (re-find #"(\w+\.)+(.*)@.*$" (str processor)) 2) result)})]
                    :while (false? result)]))
          (when-let [next-state-available-time (utils/time-after-seconds 5)]
            (when (.isAfter next-state-available-time (java.time.LocalDateTime/now))
              (async/>! log-chan {:level :info
                                  :prefix log-prefix
                                  :message (format "Sleeping until %s"
                                                   (utils/format-local-time next-state-available-time))})
              (Thread/sleep (utils/millis-between-times (java.time.LocalDateTime/now) next-state-available-time))))
          (recur)))
      (catch clojure.lang.ExceptionInfo e
        (case (:type (ex-data e))

          :network-error
          (do
            (async/>! log-chan {:level :error
                                :prefix log-prefix
                                :message (ex-message e)})
            (Thread/sleep 10000))

          (throw e))

        (async/>! error-chan e))
      (catch Exception e
        (async/>! error-chan e)))))

