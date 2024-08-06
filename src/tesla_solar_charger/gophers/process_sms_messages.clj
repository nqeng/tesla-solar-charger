(ns tesla-solar-charger.gophers.process-sms-messages
  (:require
   [cheshire.core :as json]
   [tesla-solar-charger.utils :as utils]
   [clojure.core.async :as async]
   [clj-http.client :as client]))

#_(defn get-sms-messages
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

#_(defn mark-all-as-read
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

#_(defn mark-as-read
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

#_(defn process-sms-messages
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


#_(defrecord SetPowerBuffer [set-settings-chan get-settings-chan car car-state-chan solar-sites]

  sms/SMSProcessor

  (process-sms
    [processor sms]
    (try
      (better-cond

       :let [body (get sms "body")
             match (re-find #"^\s*[bB]uffer\s+(\d+)\s*$" body)
             power-buffer-watts (Float/parseFloat (get match 1))
             power-buffer-watts (utils/clamp-min-max power-buffer-watts 0 99999)]

       :let [car-state (async/<!! car-state-chan)]

       (nil? car-state)
       (throw (ex-info "Channel closed" {}))

       :let [current-site (first (filter #(site/is-car-here? % car-state) solar-sites))]

       (nil? current-site)
       false

       :let [settings-key (str (site/get-id current-site) (car/get-vin car))
             settings-action (fn [settings]
                               (-> settings
                                   (assoc-in [settings-key "power_buffer_watts"] power-buffer-watts)))]
       (and (some? settings-action)
            (false? (async/>!! set-settings-chan settings-action)))
       (throw (ex-info "Channel closed" {}))

       :else
       true)
      (catch NumberFormatException e
        false)
      (catch NullPointerException e
        false))))

#_(defrecord SetTargetPercent [set-settings-chan get-settings-chan car car-state-chan solar-sites]

  sms/SMSProcessor

  (process-sms
    [processor sms]
    (try
      (better-cond
       :let [body (get sms "body")
             match (re-find #"^\s*[pP]ercent\s+(\d\d?\d?)\s*$" body)
             target-percent (Integer/parseInt (get match 1))
             target-percent (utils/clamp-min-max target-percent 0 100)]

       :let [car1-state (async/<!! car-state-chan)]

       (nil? car1-state)
       (throw (ex-info "Channel closed" {}))

       :let [current-site (first (filter #(site/is-car-here? % car1-state) solar-sites))]

       (nil? current-site)
       false

       :let [settings-key (str (site/get-id current-site) (car/get-vin car))
             settings-action (fn [settings]
                               (-> settings
                                   (assoc-in [settings-key "target_percent"] target-percent)))]
       (and (some? settings-action)
            (false? (async/>!! set-settings-chan settings-action)))
       (throw (ex-info "Channel closed" {}))

       :else
       true)
      (catch NumberFormatException e
        false)
      (catch java.time.DateTimeException e
        false)
      (catch NullPointerException e
        false))))

#_(defrecord SetTargetTime [set-settings-chan get-settings-chan car car-state-chan solar-sites]

  sms/SMSProcessor

  (process-sms
    [processor sms]
    (try
      (better-cond
       :let [body (get sms "body")
             match (re-find #"^\s*[tT]ime\s+(\d\d?):(\d\d?)\s*$" body)
             target-hour (Integer/parseInt (get match 1))
             target-minute (Integer/parseInt (get match 2))]

       :do (-> (java.time.LocalDateTime/now)
               (.withHour target-hour)
               (.withMinute target-minute)
               (.withSecond 0)
               (.withNano 0))

       :let [car-state (async/<!! car-state-chan)]

       (nil? car-state)
       (throw (ex-info "Channel closed" {}))

       :let [current-site (first (filter #(site/is-car-here? % car-state) solar-sites))]

       (nil? current-site)
       false

       :let [settings-key (str (site/get-id current-site) (car/get-vin car))
             settings-action (fn [settings]
                               (-> settings
                                   (assoc-in [settings-key "target_time_hour"] target-hour)
                                   (assoc-in [settings-key "target_time_minute"] target-minute)))]
       (and (some? settings-action)
            (false? (async/>!! set-settings-chan settings-action)))
       (throw (ex-info "Channel closed" {}))

       :else
       true)
      (catch NumberFormatException e
        false)
      (catch java.time.DateTimeException e
        false)
      (catch NullPointerException e
        false))))

#_(defrecord SetMaxClimb [set-settings-chan get-settings-chan car car-state-chan solar-sites]

  sms/SMSProcessor

  (process-sms
    [processor sms]
    (try
      (better-cond
       :let [car-state (async/<!! car-state-chan)]

       (nil? car-state)
       (throw (ex-info "Channel closed" {}))

       :let [body (get sms "body")
             match (re-find #"^\s*[Mm]ax\s+[Cc]limb\s+(\d\d?)\s*$" body)
             max-climb-amps (Integer/parseInt (get match 1))
             max-climb-amps (utils/limit max-climb-amps 0 (car/get-max-charge-rate-amps car-state))]

       :let [current-site (first (filter #(site/is-car-here? % car-state) solar-sites))]

       (nil? current-site)
       false

       :let [settings-key (str (site/get-id current-site) (car/get-vin car))
             settings-action (fn [settings]
                               (-> settings
                                   (assoc-in [settings-key "max_climb_amps"] max-climb-amps)))]
       (and (some? settings-action)
            (false? (async/>!! set-settings-chan settings-action)))
       (throw (ex-info "Channel closed" {}))

       :else
       true)
      (catch NumberFormatException e
        false)
      (catch java.time.DateTimeException e
        false)
      (catch NullPointerException e
        false))))

#_(defrecord SetMaxDrop [set-settings-chan get-settings-chan car car-state-chan solar-sites]

  sms/SMSProcessor

  (process-sms
    [processor sms]
    (try
      (better-cond
       :let [car-state (async/<!! car-state-chan)]

       (nil? car-state)
       (throw (ex-info "Channel closed" {}))

       :let [body (get sms "body")
             match (re-find #"^\s*[Mm]ax\s+[Dd]rop\s+(\d\d?)\s*$" body)
             max-climb-amps (Integer/parseInt (get match 1))
             max-climb-amps (utils/limit max-climb-amps 0 (car/get-max-charge-rate-amps car-state))]

       :let [current-site (first (filter #(site/is-car-here? % car-state) solar-sites))]

       (nil? current-site)
       false

       :let [settings-key (str (site/get-id current-site) (car/get-vin car))
             settings-action (fn [settings]
                               (-> settings
                                   (assoc-in [settings-key "max_drop_amps"] max-climb-amps)))]
       (and (some? settings-action)
            (false? (async/>!! set-settings-chan settings-action)))
       (throw (ex-info "Channel closed" {}))

       :else
       true)
      (catch NumberFormatException e
        false)
      (catch java.time.DateTimeException e
        false)
      (catch NullPointerException e
        false))))
