(ns tesla-solar-charger.core
  (:gen-class)
  (:require
   [tesla-solar-charger.tesla :as tesla]
   [clj-http.client :as client]
   [tesla-solar-charger.sungrow :as sungrow]
   [tesla-solar-charger.env :as env]
   [clojure.java.io :refer [make-parents]]
   [clojure.string :as str]
   [clojure.data.priority-map :refer [priority-map]]
   [cheshire.core :as json])
  (:import
   (java.time.format DateTimeFormatter)
   (java.time LocalDateTime)
   (java.time.temporal ChronoUnit)))

(defn format-time
  [format-str time]
  (.format time (DateTimeFormatter/ofPattern format-str)))

(defn time-now
  []
  (LocalDateTime/now))

(defn make-log-file-path
  [time]
  (let [year-folder (format-time "yy" time)
        month-folder (format-time "MM" time)
        log-file (format-time "yy-MM-dd" time)
        log-file-path (format "./logs/%s/%s/%s.log"
                              year-folder
                              month-folder
                              log-file)]
    log-file-path))

(defn log
  [& args]
  (let [time (time-now)
        log-timestamp (format-time "yyyy-MM-dd HH:mm:ss" time)
        log-message (format "[%s] %s" log-timestamp (str/join "\n" args))
        log-file-path (make-log-file-path time)]

    (println log-message)
    (make-parents log-file-path)
    (spit log-file-path (str log-message "\n") :append true)))

(defn limit
  [num min max]
  (cond
    (> num max) max
    (< num min) min
    :else       num))

(defn calc-new-charge-rate
  ([power-to-grid-watts
    tesla-charge-amps
    tesla-max-amps
    power-buffer-watts
    max-climb-amps
    max-drop-amps]
   (let [available-power (- power-to-grid-watts power-buffer-watts)
         adjustment-amps (-> (/ available-power tesla/power-to-current-3-phase)
                             (limit (- max-drop-amps) max-climb-amps))
         new-charge-amps (-> (+ tesla-charge-amps adjustment-amps)
                             (float)
                             (Math/round)
                             (int)
                             (limit 0 tesla-max-amps))]
     new-charge-amps))
  ([power-to-grid-watts
    tesla-charge-amps
    tesla-max-amps]
   (calc-new-charge-rate
    power-to-grid-watts
    tesla-charge-amps
    tesla-max-amps
    env/power-buffer-watts
    env/max-climb-amps
    env/max-drop-amps)))

(defn get-target-time
  [time-now]
  (-> time-now
      (.withHour env/target-time-hour)
      (.withMinute env/target-time-minute)
      (.withSecond 0)
      (.withNano 0)))

(defn calc-minutes-between-times
  [start end]
  (.until start end ChronoUnit/MINUTES))

(defn seconds-between-times
  [start end]
  (.until start end ChronoUnit/SECONDS))

(defn calc-required-current
  [tesla-state time-left-minutes]
  (-> tesla-state
      tesla/get-charge-rate
      (/ time-left-minutes)
      (* (tesla/get-minutes-to-full-charge tesla-state))
      int))

(defn fn-name
  [f]
  (clojure.string/replace (first (re-find #"(?<=\$)([^@]+)(?=@)" (str f))) "_" "-"))

(defn log-message
  [message state]
  (log message)
  [state []])

(defn do-now
  [task]
  [task (time-now)])

(defn do-after
  [seconds task]
  [task (.plusSeconds (time-now) seconds)])

;; GET SUNGROW DATA CYCLE

(declare get-solar-data)

(defn login-to-sungrow
  [state]
  (try
    (let [sungrow-token (sungrow/login)]
      (do
        (log "Logged in to Sungrow")
        [(-> state
             (assoc :sungrow-token sungrow-token))
         [(do-now get-solar-data)]]))

    (catch clojure.lang.ExceptionInfo e
      (case (:type (ex-data e))
        (do
          (log "Failed to login to Sungrow")
          [(-> state
               (dissoc :sungrow-token))
           [(do-after 10 get-solar-data)]])))))

(defn is-new-solar-data-available?
  [state]
  (let [current-data-timestamp (-> state
                                   :solar-data
                                   last
                                   :timestamp)
        latest-data-timestamp (sungrow/get-latest-data-timestamp (time-now))]
    (or
     (nil? current-data-timestamp)
     (not= latest-data-timestamp current-data-timestamp))))

(defn get-solar-data
  [state]
  (let [latest-sungrow-timestamp (sungrow/get-latest-data-timestamp (time-now))]
    (cond
      (not (is-new-solar-data-available? state))
      (do
        (log "No new Sungrow data")
        [state
         [(do-after 10 get-solar-data)]])

      (nil? (:sungrow-token state))
      (do
        (log "Sungrow not logged in; re-authenticating...")
        [state
         [(do-now login-to-sungrow)]])

      :else
      (try
        (let [excess-power-watts (sungrow/get-power-to-grid (:sungrow-token state) (time-now))
              sungrow-data [{:timestamp latest-sungrow-timestamp
                             "1152381_7_2_3" {"p8018" excess-power-watts}}]]
          (if (nil? excess-power-watts)
            (do
              (log "Sungrow data not available yet; waiting...")
              [state
               [(do-after 40 get-solar-data)]])
            (do
              (log (format "Got sungrow data point %s" latest-sungrow-timestamp))
              [(-> state
                   (assoc :previous-solar-data (:solar-data state))
                   (assoc :solar-data sungrow-data))
               [(do-after 30 get-solar-data)]])))
        (catch clojure.lang.ExceptionInfo e
          (case (:type (ex-data e))
            :network-error
            (do
              (log (ex-message e))
              [state
               [(do-after 10 get-solar-data)]])

            :err-sungrow-auth-failed
            (do
              (log (ex-message e))
              [state
               [(do-now get-solar-data)]])))))))

(declare regulate-tesla-charge)

(defn get-time-left-minutes
  []
  (calc-minutes-between-times (time-now) (get-target-time (time-now))))

(defn calc-tesla-charge-rate
  [state]
  (let [current-rate-amps (tesla/get-charge-rate (:tesla-data state))
        max-rate-amps (tesla/get-max-charge-rate (:tesla-data state))
        excess-power (-> state
                         :sungrow-data
                         first
                         :data
                         (get "1152381_7_2_3")
                         (get "p8018"))
        available-power (- excess-power env/power-buffer-watts)
        adjustment-rate-amps (-> available-power
                                 (/ tesla/power-to-current-3-phase)
                                 (limit (- env/max-drop-amps) env/max-climb-amps))
        new-rate-amps (-> current-rate-amps
                          (+ adjustment-rate-amps)
                          float
                          Math/round
                          int
                          (limit 0 max-rate-amps))
        _ (printf "Excess power: %fW%n" excess-power)
        _ (printf "Available power: %fW%n" available-power)
        _ (printf "Adjustment rate: %s%fA%n"
                  (if (neg? adjustment-rate-amps) "-" "+")
                  (abs adjustment-rate-amps))
        _ (printf "New rate: %dA%n" new-rate-amps)]
    new-rate-amps))

(defn did-tesla-stop-charging?
  [state]
  (and
   (not (nil? (:last-regulation state)))
   (not (tesla/is-charging? (:tesla-data state)))
   (tesla/is-charging? (:tesla-data (:last-regulation state)))))

(defn did-tesla-start-charging?
  [state]
  (and
   (tesla/is-charging? (:tesla-data state))
   (or
    (nil? (:last-regulation state))
    (not (tesla/is-charging? (:tesla-data (:last-regulation state)))))))

(defn has-regulated-this-solar-data?
  [state]
  (let [last-regulation (:last-regulation state)]
    (and
     (not (nil? last-regulation))
     (:used-solar-data last-regulation)
     (= (:timestamp (last (:solar-data last-regulation)))
        (:timestamp (last (:solar-data state)))))))

(defn get-excess-power
  [state]
  (-> state
      :solar-data
      last
      (get "1152381_7_2_3")
      (get "p8018")))

(defn calc-charge-rate-from-solar-output
  [state]
  (let [excess-power (get-excess-power state)
        available-power-watts (- excess-power env/power-buffer-watts)
        current-rate-amps (tesla/get-charge-rate (:tesla-data state))
        max-rate-amps (tesla/get-max-charge-rate (:tesla-data state))
        adjustment-rate-amps (-> available-power-watts
                                 (/ tesla/power-to-current-3-phase)
                                 (limit (- env/max-drop-amps) env/max-climb-amps))
        new-rate-amps (-> current-rate-amps
                          (+ adjustment-rate-amps)
                          float
                          Math/round
                          int
                          (limit 0 max-rate-amps))]
    new-rate-amps))

(defn create-regulation-from-excess-solar
  [state]
  (let [regulation {:solar-data (:solar-data state)
                    :tesla-data (:tesla-data state)
                    :used-solar-data false
                    :new-rate-amps nil
                    :current-rate-amps (tesla/get-charge-rate (:tesla-data state))
                    :message nil}]

    (cond
      (nil? (:solar-data state))
      regulation

      (has-regulated-this-solar-data? state)
      regulation

      (nil? (get-excess-power state))
      regulation

      :else
      (let [new-rate-amps (calc-charge-rate-from-solar-output state)]
        (-> regulation
            (assoc :used-solar-data true)
            (assoc :new-rate-amps new-rate-amps)
            (assoc :message (format "%fW of available power; setting charge speed to %dA"
                                    (- (get-excess-power state) env/power-buffer-watts)
                                    new-rate-amps)))))))

(defn create-charge-rate-regulation
  [state]
  (let [max-charge-rate-amps (tesla/get-max-charge-rate (:tesla-data state))
        current-charge-rate-amps (tesla/get-charge-rate (:tesla-data state))
        starting-charge-rate-amps 0
        regulation {:current-rate-amps current-charge-rate-amps
                    :used-solar-data false
                    :solar-data (:solar-data state)
                    :tesla-data (:tesla-data state)
                    :new-rate-amps nil
                    :message nil}]

    (cond
      (nil? (:tesla-data state))
      regulation

      (did-tesla-stop-charging? state)
      (-> regulation
          (assoc :new-rate-amps max-charge-rate-amps)
          (assoc :message (format "Tesla stopped charging; resetting to max rate (%dA)"
                                  max-charge-rate-amps)))

      (did-tesla-start-charging? state)
      (-> regulation
          (assoc :new-rate-amps starting-charge-rate-amps)
          (assoc :message (format "Tesla started charging; beginning at starting rate (%dA)"
                                  starting-charge-rate-amps)))

      (tesla/is-charging-complete? (:tesla-data state))
      (-> regulation
          (assoc :message (format "Charging complete")))

      (not (tesla/is-charging? (:tesla-data state)))
      (-> regulation
          (assoc :message (format "Tesla is not charging")))

      (tesla/is-override-active? (:tesla-data state))
      (-> regulation
          (assoc :new-rate-amps max-charge-rate-amps)
          (assoc :message (format "User override active; charging at max rate (%dA)"
                                  max-charge-rate-amps)))

      (<= (get-time-left-minutes) (tesla/get-minutes-to-full-charge-at-max-rate (:tesla-data state)))
      (-> regulation
          (assoc :new-rate-amps max-charge-rate-amps)
          (assoc :message (format "Auto override active to reach %s%% by %d:%d; charging at max rate (%dA)"
                                  env/target-percent
                                  env/target-time-hour
                                  env/target-time-minute
                                  max-charge-rate-amps)))

      :else
      (create-regulation-from-excess-solar state))))

(defn regulate-tesla-charge
  [state]
  (let [regulation (create-charge-rate-regulation state)]
    (when (not (nil? (:message regulation)))
      (log (:message regulation)))
    (if (nil? (:new-rate-amps regulation))
      [state [(do-after 10 regulate-tesla-charge)]]
      (try
        (if (not= (:new-rate-amps regulation) (:current-rate-amps regulation))
          (do
            ;(tesla/set-charge-amps (:new-rate-amps regulation))
            (spit "test.json" (-> state
                                  :tesla-data
                                  (assoc-in ["charge_state" "charge_amps"] (:new-rate-amps regulation))
                                  (json/generate-string {:pretty true})))
            (log "Set Tesla charge rate"))
          (log "No change to charge rate"))
        [(-> state
             (assoc :last-regulation regulation))
         [(do-after 10 regulate-tesla-charge)]]
        (catch clojure.lang.ExceptionInfo e
          [state
           [(do-after 10 regulate-tesla-charge)]])))))

(defn update-tesla-state
  [state]
  (log "Updating Tesla state")
  (try
    (let [;tesla-state (tesla/get-data)
          tesla-state (json/parse-string (slurp "test.json"))
          new-state (-> state
                        (assoc :tesla-data tesla-state))]
      (if (not (tesla/is-near-charger? tesla-state))
        (do
          (log "Tesla is not near the charger")
          [new-state
           [(do-after 10 update-tesla-state)]])
        [new-state
         [(do-after 10 update-tesla-state)]]))

    (catch clojure.lang.ExceptionInfo e
      (case (:type (ex-data e))
        :err-could-not-get-tesla-state
        (do
          (log (ex-message e))
          [state
           [(do-after 10 update-tesla-state)]])
        :network-error
        (do
          (log (ex-message e))
          [state
           [(do-after 10 update-tesla-state)]])
        (throw e)))))

(defn log-to-thingspeak
  [& data]
  (let [field-names (take-nth 2 data)
        field-values (take-nth 2 (rest data))
        url "https://api.thingspeak.com/update"
        query-params (into {:api_key env/thingspeak-api-key}
                           (map (fn [key value] {key value}) field-names field-values))]
    (try
      (client/get url {:query-params query-params})
      (catch java.net.UnknownHostException e
        (let [error (.getMessage e)]
          (throw (ex-info
                  (str "Network error; " error)
                  {:type :network-error}))))
      (catch java.net.NoRouteToHostException e
        (let [error (.getMessage e)]
          (throw (ex-info
                  (str "Failed to get Tesla state; " error)
                  {:type :network-error})))))))

(defn publish-thingspeak-data
  [state]
  (log "Publishing data to thingspeak")
  (println (:solar-data state))
  (try
    (let [power-to-grid (get-in (last (:solar-data state)) ["1152381_7_2_3" "p8018"] 0)
          _ (println power-to-grid)
          charge-rate-amps (get-in (:tesla-data state) ["charge_state" "charge_amps"] 0)
          battery-percent (get-in (:tesla-data state) ["charge_state" "battery_level"] 0)
          power-to-tesla (* charge-rate-amps tesla/power-to-current-3-phase)]
      (try
        (log-to-thingspeak
         "field1" (float power-to-grid)
         "field2" (float charge-rate-amps)
         "field3" (float battery-percent)
         "field4" (float power-to-tesla))
        (catch clojure.lang.ExceptionInfo e
          (do
            (log (ex-message e))
            [state
             [(do-after 30 publish-thingspeak-data)]])))

      [state
       [(do-after 30 publish-thingspeak-data)]])

    (catch java.net.UnknownHostException e
      (do
        (log (format "Network error; %s" (.getMessage e)))
        [state
         [(do-after 10 publish-thingspeak-data)]]))

    (catch java.net.NoRouteToHostException e
      (do
        (log (format "Network error; %s" (.getMessage e)))
        [state
         [(do-after 10 publish-thingspeak-data)]]))

    (catch clojure.lang.ExceptionInfo e
      (case (:type (ex-data e))
        (throw e)))))

(defn main-loop
  [state actions]
  (let [[next-action scheduled-time] (peek actions)]
    (if (.isAfter scheduled-time (time-now))
      [state actions]
      (let [actions (pop actions)
            [new-state next-actions] (next-action state)
            new-actions (apply conj actions next-actions)]
        [new-state new-actions]))))

(def initial-actions
  [(do-now (partial log-message "Started"))
   (do-now update-tesla-state)
   (do-now login-to-sungrow)
   (do-after 10 regulate-tesla-charge)
   (do-after 10 publish-thingspeak-data)])

(defn -main
  [& args]
  (log "Starting...")

  (loop [state {} actions (into (priority-map) initial-actions)]
    (let [[new-state new-actions]
          (try
            (main-loop state actions)
            (catch Exception e
              (do
                (log (.getMessage e))
                (throw e)))
            (catch clojure.lang.ExceptionInfo e
              (do
                (log (ex-message e))
                (throw e))))]
      (recur new-state new-actions))))

