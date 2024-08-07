(ns tesla-solar-charger.gophers.process-sms-messages
  (:require
   [cheshire.core :as json]
   [taoensso.timbre :as timbre]
   [tesla-solar-charger.utils :as utils]
   [clojure.core.async :refer [go >! alts!]]
   [clj-http.client :as client]))

(defn get-sms-messages
  [clicksend-username clicksend-api-key]
  (-> (client/get
         "https://rest.clicksend.com/v3/sms/inbound"
         {:basic-auth [clicksend-username clicksend-api-key]})
        :body
        json/parse-string
        (get "data")
        (get "data")))

(defn get-sent-messages
  [clicksend-username clicksend-api-key]
  (let [url "https://rest.clicksend.com/v3/sms/history"
        response (client/get url {:basic-auth [clicksend-username clicksend-api-key]})
        messages (-> response
                     :body
                     json/parse-string
                     (get "data")
                     (get "data"))]
    messages))

(defn get-last-sent-message-date
  [clicksend-username clicksend-api-key]
  (-> (get-sent-messages clicksend-username clicksend-api-key)
      last
      (get "date")
      java.time.Instant/ofEpochSecond))

(defn mark-all-as-read
  [clicksend-username clicksend-api-key]
  (client/put
     "https://rest.clicksend.com/v3/sms/inbound-read"
     {:basic-auth [clicksend-username clicksend-api-key]}))

(defn mark-as-read
  [clicksend-username clicksend-api-key sms-message]
  (client/put
     (str "https://rest.clicksend.com/v3/sms/inbound-read/" (get sms-message "message_id"))
     {:basic-auth [clicksend-username clicksend-api-key]}))

(defn perform-and-return-error
  [foo]
  (try
    (let [result (foo)]
      {:err nil :val result})
    (catch clojure.lang.ExceptionInfo err
      {:err err :val nil})
    (catch Exception err
      {:err err :val nil})))

(defn fetch-new-sms-messages
  [clicksend-username clicksend-api-key output-ch kill-ch log-prefix]
  (letfn [(info [msg] (timbre/info (format "[%s]" log-prefix) msg))
          (error [msg] (timbre/error (format "[%s]" log-prefix) msg))
          (debug [msg] (timbre/debug (format "[%s]" log-prefix) msg))]
    (go
      (info "Process starting...")
      (try
        (mark-all-as-read clicksend-username clicksend-api-key)
        (info "Marked old messages as read")
        (loop [sleep-for 0]
          (let [func (partial get-sms-messages clicksend-username clicksend-api-key)
                result-ch (go
                            (Thread/sleep sleep-for)
                            (perform-and-return-error func))
                [val ch] (alts! [kill-ch result-ch])]
            (if (= ch kill-ch)
              (info "Process dying...")
              (let [{err :err messages :val} val]
                (if (some? err)
                  (do
                    (error (format "Failed to fetch messages; %s" (ex-message err)))
                    (recur 10000))
                  (if (empty? messages)
                    (do
                      (info "No new messages")
                      (recur 10000))
                    (do
                      (info (format "Fetched %s messages" (count messages)))
                      (try
                        (mark-all-as-read clicksend-username clicksend-api-key)
                        (info "Marked messages as read")
                        (catch clojure.lang.ExceptionInfo e
                          (error (format "Failed to mark messages as read; %s" (ex-message e))))
                        (catch Exception e
                          (error (format "Failed to mark messages as read; %s" (ex-message e)))))
                      (doseq [message messages]
                        (>! output-ch message))
                      (recur 10000))))))))
        (catch clojure.lang.ExceptionInfo e
          (error (format "Failed to mark old messages as read; %s" (ex-message e))))
        (catch Exception e
          (error (format "Failed to mark old messages as read; %s" (ex-message e)))))
      (info "Process died"))
    )
  )

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
