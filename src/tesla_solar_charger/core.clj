(ns tesla-solar-charger.core
  (:gen-class)
  (:require
   [tesla-solar-charger.tesla :as tesla]
   [clj-http.client :as client]
   [tesla-solar-charger.sungrow :as sungrow]
   [tesla-solar-charger.env :as env]
   [clojure.java.io :refer [make-parents]]
   [clojure.string :as str]
   [clojure.data.priority-map :refer [priority-map]])
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

(declare get-sungrow-data)

(sungrow/login)

(defn login-to-sungrow
  [state]
  (try
    (let [sungrow-token (sungrow/login)]
      [(-> state
           (assoc :sungrow-token sungrow-token))
       [(do-now (partial log-message "Logged in to Sungrow"))
        (do-now get-sungrow-data)]])

    (catch clojure.lang.ExceptionInfo e
      (case (:type (ex-data e))
        [(-> state
             (dissoc :sungrow-token))
         [(do-now (partial log-message "Failed to login to Sungrow"))
          (do-after 10 get-sungrow-data)]]))))

(defn get-sungrow-data
  [state]
  (let [latest-sungrow-data-point-id (sungrow/get-latest-data-timestamp (time-now))]
    (if (and (not (nil? (:last-data-point state))) (not (= latest-sungrow-data-point-id (:last-data-point state))))
      [state
       [(do-now (partial log-message "No new Sungrow data"))
        (do-after 10 get-sungrow-data)]]
      (if (nil? (:sungrow-token state))
        [state
         [(do-now (partial log-message "Sungrow not logged in; re-authenticating..."))
          (do-now login-to-sungrow)]]
        (try
          (let [sungrow-data (sungrow/get-power-to-grid (:sungrow-token state) latest-sungrow-data-point-id)]
            (if (nil? sungrow-data)
              [state
               [(do-now (partial log-message "Sungrow data not available yet; waiting..."))
                (do-after 40 get-sungrow-data)]]
              [(-> state
                   (assoc :power-to-grid sungrow-data))
               [(do-now (partial log-message (format "Got sungrow data point: %s" latest-sungrow-data-point-id)))
                (do-after 30 get-sungrow-data)]]))

          (catch java.net.UnknownHostException e
            [state
             [(do-now (partial log-message (format "Network error; %s" (.getMessage e))))
              (do-after 10 get-sungrow-data)]])

          (catch java.net.NoRouteToHostException e
            [state
             [(do-now (partial log-message (format "Network error; %s" (.getMessage e))))
              (do-after 10 get-sungrow-data)]])

          (catch clojure.lang.ExceptionInfo e
            (case (:type (ex-data e))
              :err-sungrow-auth-failed
              [state
               [(do-now get-sungrow-data)]]
              (throw e))))))))

(declare regulate-tesla-charge-rate)

(defn get-time-left-minutes
  [state]
  (calc-minutes-between-times ((time-now) state) (get-target-time ((time-now) state))))

(defn calc-tesla-charge-rate
  [state]
  (-> (:power-to-grid state)
      (- env/power-buffer-watts)
      (limit (- env/max-drop-amps) (env/max-climb-amps))
      (+ (tesla/get-charge-rate (:tesla-state state)))
      float
      Math/round
      int
      (limit 0 (tesla/get-max-charge-rate (:tesla-state state)))))

(defn set-tesla-charge-rate
  [new-charge-rate state]
  (if (= new-charge-rate (tesla/get-charge-rate (:tesla-state state)))
    [state
     [(do-now (partial log-message "No change to Tesla charge rate%n"))
      regulate-tesla-charge-rate]]
    (try
      (tesla/set-charge-amps new-charge-rate)
      [state
       [(do-now (partial log-message (format "Set tesla charge rate to %dA" new-charge-rate)))]]

      (catch java.net.UnknownHostException e
        [state
         [(do-now (partial log-message (format "Network error; %s" (.getMessage e))))
          (do-after 10 regulate-tesla-charge-rate)]])

      (catch java.net.NoRouteToHostException e
        [state
         [(do-now (partial log-message (format "Network error; %s" (.getMessage e))))
          (do-after 10 regulate-tesla-charge-rate)]])

      (catch clojure.lang.ExceptionInfo e
        (case (:type (ex-data e))
          :err-could-not-set-charge-amps
          [state
           [(do-now (partial log-message "Failed to set Tesla charge rate"))
            (do-after 10 regulate-tesla-charge-rate)]]
          (throw e))))))

(defn did-tesla-stop-charging?
  [state]
  (and
   (not (tesla/is-charging? (:tesla-state state)))
   (tesla/is-charging? (:previous-tesla-state state))))

(defn did-tesla-start-charging?
  [state]
  (and
   (tesla/is-charging? (:tesla-state state))
   (not (tesla/is-charging? (:previous-tesla-state state)))))

(defn regulate-tesla-charge-rate
  [state]
  (cond
    (nil? (:tesla-state state))
    [state
     [(do-after 10 regulate-tesla-charge-rate)]]

    (not (tesla/is-near-charger? (:tesla-state state)))
    [state
     [(do-now (partial log-message "Tesla is not near the charger"))
      (do-after 60 regulate-tesla-charge-rate)]]

    (did-tesla-stop-charging? state)
    [state
     [(do-now (partial log-message "Tesla began charging; setting charge rate to max"))
      (do-now (partial set-tesla-charge-rate (tesla/get-max-charge-rate (:tesla-state state))))]]

    (did-tesla-start-charging? state)
    [state
     [(do-now (partial log-message "Tesla began charging; setting charge rate to zero"))
      (do-now (partial set-tesla-charge-rate 0))]]

    (tesla/is-charging-complete? (:tesla-state state))
    [state
     [(do-now (partial log-message "Tesla has finished charging"))
      (do-now regulate-tesla-charge-rate)]]

    (not (tesla/is-charging? (:tesla-state state)))
    [state
     [(do-now (partial log-message "Tesla is not charging"))
      (do-now regulate-tesla-charge-rate)]]

    (tesla/is-override-active? (:tesla-state state))
    [state
     [(do-now (partial log-message "Override active; setting charge rate to max"))
      (do-now (partial set-tesla-charge-rate (tesla/get-max-charge-rate (:tesla-state state))))]]

    (<= (get-time-left-minutes state) (tesla/get-minutes-to-full-charge-at-max-rate (:tesla-state state)))
    [state
     [(do-now (partial log-message "Overriding to reach target; setting charge rate to max"))
      (do-now (partial set-tesla-charge-rate (tesla/get-max-charge-rate (:tesla-state state))))]]

    (nil? (:power-to-grid state))
    [state
     [(do-now regulate-tesla-charge-rate)]]

    :else
    [state
     [(do-now (partial set-tesla-charge-rate (calc-tesla-charge-rate state)))]]))

(defn update-tesla-state
  [state]
  (try
    (let [_ (log-message "Updating Tesla state" state)
          tesla-state (tesla/get-data)
          state (-> state
                    (assoc :previous-tesla-state (:tesla-state state))
                    (assoc :tesla-state tesla-state))]

      (if (not (tesla/is-near-charger? tesla-state))
        [state
         [(do-now (partial log-message "Updated Tesla state"))
          (do-after 60 update-tesla-state)]]
        (if (not (tesla/is-charging? tesla-state))
          [state
           [(do-now (partial log-message "Updated Tesla state"))
            (do-after 10 update-tesla-state)]]
          [state
           [(do-now (partial log-message "Updated Tesla state"))
            (do-after 5 update-tesla-state)]])))

    (catch java.net.UnknownHostException e
      [state
       [(do-now (partial log-message (format "Network error; %s" (.getMessage e))))]])

    (catch java.net.NoRouteToHostException e
      [state
       [(do-now (partial log-message (format "Network error; %s" (.getMessage e))))]])

    (catch clojure.lang.ExceptionInfo e
      (case (:type (ex-data e))
        :err-could-not-get-tesla-state
        [state
         [(do-now (partial log-message "Could not get tesla state"))
          (do-now update-tesla-state)]]
        (throw e)))))

(defn log-to-thingspeak
  [& data]
  (let [field-names (take-nth 2 data)
        field-values (take-nth 2 (rest data))
        url "https://api.thingspeak.com/update"
        query-params (into {:api_key env/thingspeak-api-key}
                           (map (fn [key value] {key value}) field-names field-values))]
    (client/get url {:query-params query-params})))

(defn publish-thingspeak-data
  [state]
  (try
    (let [power-to-grid (get state :power-to-grid 0)
          charge-rate-amps (get-in (:tesla-state state) ["charge_state" "charge_amps"] 0)
          battery-percent (get-in (:tesla-state state) ["charge_state" "battery_level"] 0)
          power-to-tesla (* charge-rate-amps tesla/power-to-current-3-phase)]
      (log-message "Publishing data to thingspeak" state)
      (log-to-thingspeak
       "field1" (float power-to-grid)
       "field2" (float charge-rate-amps)
       "field3" (float battery-percent)
       "field4" (float power-to-tesla))
      (log-message "Published data to thingspeak" state)
      [state
       [(do-after 30 publish-thingspeak-data)]])

    (catch java.net.UnknownHostException e
      [state
       [(do-now (partial log-message (format "Network error; %s" (.getMessage e))))]])

    (catch java.net.NoRouteToHostException e
      [state
       [(do-now (partial log-message (format "Network error; %s" (.getMessage e))))]])

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
   (do-now get-sungrow-data)
   (do-after 10 regulate-tesla-charge-rate)
   (do-after 10 publish-thingspeak-data)])

(defn -main
  [& args]
  (log "Starting...")

  (loop [state {} actions (into (priority-map) initial-actions)]
    (try
      (let [[new-state new-actions] (main-loop state actions)]
        (recur new-state new-actions))
      (catch Exception e
        (log (.getMessage e))
        (throw e))
      (catch clojure.lang.ExceptionInfo e
        (log (ex-message e))
        (throw e)))))

