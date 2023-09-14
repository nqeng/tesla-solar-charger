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
   [cheshire.core :as json]
   [clojure.core.async :as async])
  (:import
   (java.time.format DateTimeFormatter)
   (java.time LocalDateTime)
   (java.time.temporal ChronoUnit)))

(defn format-time
  [format-str time]
  (.format time (DateTimeFormatter/ofPattern format-str)))

(import (java.time LocalDateTime))

(import (java.time.temporal ChronoUnit))

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
  [type & args]
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

(defn millis-between-times
  [start end]
  (.until start end ChronoUnit/MILLIS))

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
        :err-sungrow-login-auth
        (throw e)

        :err-sungrow-login-too-frequent
        (do
          (log (ex-message e))
          [state
           [(do-after 30 login-to-sungrow)]])

        :err-sungrow-login-other
        (do
          (log (ex-message e))
          [state
           [(do-after 10 login-to-sungrow)]])

        (throw e)))))

(defn is-new-solar-data-available?
  [state]
  (let [current-data-timestamp (-> state
                                   :solar-data
                                   last
                                   :timestamp)
        latest-data-timestamp (sungrow/get-last-data-timestamp (time-now))]
    (or
     (nil? current-data-timestamp)
     (not= latest-data-timestamp current-data-timestamp))))

(defn get-solar-data
  [state]
  (log :verbose "Getting solar data")
  (let [latest-sungrow-timestamp (sungrow/get-last-data-timestamp (time-now))]
    (cond
      (not (is-new-solar-data-available? state))
      (do
        (log "No new Sungrow data")
        [state
         [(do-after 10 get-solar-data)]])

      (nil? (:sungrow-token state))
      (do
        (log :debug "Sungrow not logged in; re-authenticating...")
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
               [(do-now login-to-sungrow)]])

            (throw e)))))))

(declare regulate-tesla-charge)

(defn get-time-left-minutes
  []
  (calc-minutes-between-times (time-now) (get-target-time (time-now))))

(defn did-tesla-stop-charging?
  [state]
  (and
   (not (nil? (:last-regulation state)))
   (or
    (not (tesla/is-charging? (:tesla-state state)))
    (not (tesla/is-near-charger? (:tesla-state state))))
   (tesla/is-charging? (:tesla-state (:last-regulation state)))
   (tesla/is-near-charger? (:tesla-state (:last-regulation state)))))

(defn did-tesla-start-charging?
  [state]
  (and
   (tesla/is-charging? (:tesla-state state))
   (tesla/is-near-charger? (:tesla-state state))
   (or
    (nil? (:last-regulation state))
    (not (tesla/is-charging? (:tesla-state (:last-regulation state))))
    (not (tesla/is-near-charger? (:tesla-state (:last-regulation state)))))))

(defn has-regulated-this-solar-data?
  [state]
  (let [last-regulation (:last-regulation state)]
    (and
     (not (nil? last-regulation))
     (:used-solar-data last-regulation)
     (:was-successful last-regulation)
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
        current-rate-amps (tesla/get-charge-rate (:tesla-state state))
        max-rate-amps (tesla/get-max-charge-rate (:tesla-state state))
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

(def new-regulation
  {:save-initial-state false
   :current-rate-amps 0
   :used-solar-data false
   :solar-data nil
   :tesla-state nil
   :new-rate-amps nil
   :message nil})

(defn create-regulation-from-excess-solar
  [state]
  (let [current-charge-rate-amps (tesla/get-charge-rate (:tesla-state state))
        regulation (-> new-regulation
                       (assoc :current-rate-amps current-charge-rate-amps
                              :solar-data (:solar-data state)
                              :tesla-state (:tesla-state state)))]

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
  (let [max-charge-rate-amps (tesla/get-max-charge-rate (:tesla-state state))
        current-charge-rate-amps (tesla/get-charge-rate (:tesla-state state))
        starting-charge-rate-amps 0
        regulation (-> new-regulation
                       (assoc :current-rate-amps current-charge-rate-amps
                              :solar-data (:solar-data state)
                              :tesla-state (:tesla-state state)))]

    (cond
      (nil? (:tesla-state state))
      regulation

      (not (tesla/is-near-charger? (:tesla-state state)))
      (-> regulation
          (assoc :message (format "Tesla is not near the charger")))

      (did-tesla-stop-charging? state)
      (-> regulation
          (assoc :new-rate-amps (tesla/get-charge-rate (:initial-tesla-state state)))
          (assoc :message (format "Tesla stopped charging; resetting to max rate (%dA)"
                                  max-charge-rate-amps)))

      (did-tesla-start-charging? state)
      (-> regulation
          (assoc :new-rate-amps starting-charge-rate-amps)
          (assoc :message (format "Tesla started charging; beginning at starting rate (%dA)"
                                  starting-charge-rate-amps)))

      (tesla/is-charging-complete? (:tesla-state state))
      (-> regulation
          (assoc :message (format "Charging complete")))

      (not (tesla/is-charging? (:tesla-state state)))
      (-> regulation
          (assoc :message (format "Tesla is not charging")))

      (tesla/is-override-active? (:tesla-state state))
      (-> regulation
          (assoc :new-rate-amps max-charge-rate-amps)
          (assoc :message (format "User override active; charging at max rate (%dA)"
                                  max-charge-rate-amps)))

      (<= (get-time-left-minutes) (tesla/get-minutes-to-full-charge-at-max-rate (:tesla-state state)))
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
  (log :verbose "Regulating charge speed...")
  (let [regulation (create-charge-rate-regulation state)]
    (when (not (nil? (:message regulation)))
      (log (:message regulation)))
    (if (nil? (:new-rate-amps regulation))
      [state [(do-after 10 regulate-tesla-charge)]]
      (if (= (:new-rate-amps regulation) (:current-rate-amps regulation))
        (do
          (log "No change to charge rate")
          [(-> state
               (assoc :last-regulation (assoc regulation :was-successful true)))
           [(do-after 10 regulate-tesla-charge)]])
        (try
            ;(tesla/set-charge-amps (:new-rate-amps regulation))
          (spit "test.json" (-> state
                                :tesla-state
                                (assoc-in ["charge_state" "charge_amps"] (:new-rate-amps regulation))
                                (json/generate-string {:pretty true})))
          (log "Set Tesla charge rate")
          [(-> state
               (assoc :last-regulation (assoc regulation :was-successful true)))
           [do-after 10 regulate-tesla-charge]]
          (catch clojure.lang.ExceptionInfo e
            (case (:type (ex-data e))
              :network-error
              [(-> state
                   (assoc :last-regulation (assoc regulation :was-successful false)))
               [do-after 30 regulate-tesla-charge]]

              (throw e))))))))

(defn update-tesla-data
  [tesla->regulator tesla->logger]
  (let [result {}]
    (try
      (let [tesla-state
            #_(tesla/get-data) (json/parse-string (slurp "test.json"))
            delay (cond
                    (not (tesla/is-near-charger? tesla-state))
                    60000

                    (not (tesla/is-charging? tesla-state))
                    30000

                    :else
                    10000)
            result (-> result
                       (assoc :tesla-state tesla-state)
                       (assoc :delay delay))]

        (cond
          (false? (async/>! tesla->regulator tesla-state))
          (throw (ex-info "Channel tesla->regulator closed!" {}))

          (false? (async/>! tesla->logger tesla-state))
          (throw (ex-info "Channel tesla->logger closed!" {}))

          :else
          (-> result
              (assoc :message "Got Tesla data"))))

      (catch clojure.lang.ExceptionInfo e
        (case (:type (ex-data e))

          (throw e)))
      (catch Exception e
        (throw e)))))

(defn update-tesla-data-loop
  [tesla->regulator tesla->logger error-chan]
  (try
    (loop [last-result nil]
      (log :verbose "Updating Tesla state")
      (let [result (update-tesla-data tesla->regulator tesla->logger)]
        (when (not (nil? (:message result)))
          (log :info (format "[Tesla] %s%n" (:message result))))

        (when (not (nil? (:delay result)))
          (log :info (format "[Tesla] Waiting for %dms%n" (:delay result)))
          (Thread/sleep (:delay result)))

        (recur result)))
    (catch clojure.lang.ExceptionInfo e
      (async/>!! error-chan e))
    (catch Exception e
      (async/>!! error-chan e))))

(defn update-solar-data
  [solar->regulator solar->logger sungrow-token]
  (let [result {}]

    (if (nil? sungrow-token)
      (try
        (let [sungrow-token (sungrow/login)]
          (-> result
              (assoc :message "Logged in to Sungrow")
              (assoc :sungrow-token sungrow-token)))
        (catch clojure.lang.ExceptionInfo e
          (case (:type (ex-data e))

            :err-sungrow-login-too-frequent
            (-> result
                (assoc :message (ex-message e))
                (assoc :delay 60000))

            (throw e)))
        (catch Exception e
          (throw e)))

      (try
        (let [current-time (time-now)
              solar-data (sungrow/get-power-to-grid sungrow-token current-time)
              result (assoc result :solar-data solar-data)
              next-data-publish-time (sungrow/get-next-data-publish-time current-time)
              millis-until-next-data (-> (millis-between-times current-time next-data-publish-time)
                                         (.plusSeconds 40))]
          (cond
            (nil? solar-data)
            (-> result
                (assoc :message "Solar data not published yet")
                (assoc :delay 40000))

            (false? (async/>!! solar->regulator solar-data))
            (throw (ex-info "Channel solar->regulator closed!" {}))

            (false? (async/>!! solar->logger solar-data))
            (throw (ex-info "Channel solar->logger closed!" {}))

            :else
            (-> result
                (assoc :message "Got Sungrow data")
                (assoc :delay millis-until-next-data))))
        (catch clojure.lang.ExceptionInfo e
          (case (:type (ex-data e))

            :err-sungrow-auth-failed
            (-> result
                (assoc :message "Sungrow not logged in")
                (assoc :sungrow-token nil))

            (throw e)))
        (catch Exception e
          (throw e))))))

(defn update-solar-data-loop
  [solar->regulator solar->logger error-chan]
  (try
    (loop [last-result nil]
      (log :verbose "Updating solar data")
      (let [sungrow-token (:sungrow-token last-result)
            result (update-solar-data solar->regulator solar->logger sungrow-token)]
        (when (not (nil? (:message result)))
          (log :info (format "[Solar] %s" (:message result))))

        (when (not (nil? (:delay result)))
          (log :info (format "[Solar] Waiting for %dms%n" (:delay result)))
          (Thread/sleep (:delay result)))

        (recur result)))
    (catch clojure.lang.ExceptionInfo e
      (async/>!! error-chan e))
    (catch Exception e
      (async/>!! error-chan e))))

(defn did-tesla-start-charging?
  [current-data previous-data]
  (and
   (tesla/is-charging? current-data)
   (tesla/is-near-charger? current-data)
   (or
    (nil? previous-data)
    (not (tesla/is-charging? previous-data))
    (not (tesla/is-near-charger? previous-data)))))

(defn did-tesla-stop-charging?
  [current-data previous-data]
  (and
   (not (nil? previous-data))
   (tesla/is-charging? previous-data)
   (tesla/is-near-charger? previous-data)
   (or
    (not (tesla/is-charging? current-data))
    (not (tesla/is-near-charger? current-data)))))

(require '[clojure.core.async :as async])

(defn has-regulated-this-solar-data?
  [solar-data last-regulation]
  (and
   (not (nil? last-regulation))
   (:used-solar-data last-regulation)
   (= (:timestamp (last (:solar-data last-regulation)))
      (:timestamp (last solar-data)))))

(defn regulate-tesla-charge
  [tesla->regulator solar->regulator last-regulation first-regulation solar-data]
  (let [result {:delay 10000}]
    (try
      (let [[value channel] (async/alts!! [tesla->regulator tesla->regulator])]

        (cond
          (nil? value)
          (throw (ex-info "Channel closed!" {}))

          (= channel solar->regulator)
          (-> result
              (assoc :solar-data value))

          :else
          (let [tesla-data value
                regulation {:solar-data solar-data
                            :tesla-data tesla-data}]
            (cond
              (did-tesla-start-charging? tesla-data (:tesla-data last-regulation))
              (do
                ; set Tesla charge limit
                (tesla/set-charge-rate 0)
                (-> result
                    (assoc :regulation (-> regulation
                                           (assoc :new-charge-rate-amps  0)
                                           (assoc :new-charge-limit-percent 0)))
                    (assoc :message "Set charge amps to 0")
                    (assoc :started-regulating true)))

              (did-tesla-stop-charging? tesla-data (:tesla-data last-regulation))
              (do
                (tesla/set-charge-rate (tesla/get-charge-rate (:tesla-data first-regulation)))
                ; set Tesla charge limit
                (-> result
                    (assoc :regulation (-> regulation
                                           (assoc :new-charge-rate-amps  0)
                                           (assoc :new-charge-limit-percent 0)))
                    (assoc :message "Reset charge amps to before")))

              (not (tesla/is-near-charger? tesla-data))
              (-> result
                  (assoc :message "Tesla is not near charger"))

              (not (tesla/is-charging? tesla-data))
              (-> result
                  (assoc :message "Tesla is not charging"))

              (tesla/is-override-active? tesla-data)
              (do
                (tesla/set-charge-rate (tesla/get-max-charge-rate tesla-data))
                (-> result
                    (assoc :regulation (-> regulation
                                           (assoc :new-charge-rate-amps  0)))
                    (assoc :message "User override active")))

              (<= (get-time-left-minutes) (tesla/get-minutes-to-full-charge-at-max-rate tesla-data))
              (do
                (tesla/set-charge-rate (tesla/get-max-charge-rate tesla-data))
                (-> result
                    (assoc :regulation (-> regulation
                                           (assoc :new-charge-rate-amps  0)))
                    (assoc :message "Auto override active")))

              (has-regulated-this-solar-data? solar-data last-regulation)
              (-> result
                  (assoc :message "Already processed this solar data"))

              (nil? (get-excess-power solar-data))
              (-> result
                  (assoc :message "Solar data was nil"))

              :else
              (let [excess-power (get-excess-power solar-data)
                    available-power-watts (- excess-power env/power-buffer-watts)
                    current-rate-amps (tesla/get-charge-rate tesla-data)
                    max-rate-amps (tesla/get-max-charge-rate tesla-data)
                    adjustment-rate-amps (-> available-power-watts
                                             (/ tesla/power-to-current-3-phase)
                                             (limit (- env/max-drop-amps) env/max-climb-amps))
                    new-rate-amps (-> current-rate-amps
                                      (+ adjustment-rate-amps)
                                      float
                                      Math/round
                                      int
                                      (limit 0 max-rate-amps))]
                (-> result
                    (assoc :regulation (-> regulation
                                           (assoc :new-charge-rate-amps new-rate-amps)
                                           (assoc :used-solar-data true)))
                    (assoc :message "Done")))))))

      (catch clojure.lang.ExceptionInfo e
        (case (:type (ex-data e))
          (throw e)))

      (catch Exception e
        (throw e)))))

(defn regulate-tesla-charge-loop
  [tesla->regulator solar->regulator error-chan]
  (try
    (loop [last-result nil
           last-regulation nil
           first-regulation nil
           solar-data nil]
      (log :verbose "Regulating tesla data")
      (let [result (regulate-tesla-charge tesla->regulator solar->regulator last-regulation last-regulation solar-data)
            last-regulation (if (not (nil? (:regulation result)))
                              (:regulation result)
                              last-regulation)
            first-regulation (if (:started-regulating result)
                               (:regulation result)
                               first-regulation)
            solar-data (if (:solar-data result)
                         (:solar-data result)
                         solar-data)]

        (when (not (nil? (:message result)))
          (log :info (format "[Regulator] %s" (:message result))))

        (when (not (nil? (:delay result)))
          (log :info (format "[Regulator] Waiting for %dms%n" (:delay result)))
          (Thread/sleep (:delay result)))

        (recur result)))
    (catch clojure.lang.ExceptionInfo e
      (async/>!! error-chan e))
    (catch Exception e
      (async/>!! error-chan e))))

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
  (let [power-to-grid (get-in (last (:solar-data state)) ["1152381_7_2_3" "p8018"] 0)
        charge-rate-amps (get-in (:tesla-state state) ["charge_state" "charge_amps"] 0)
        battery-percent (get-in (:tesla-state state) ["charge_state" "battery_level"] 0)
        power-to-tesla (* charge-rate-amps tesla/power-to-current-3-phase)]
    (try
      (log-to-thingspeak
       "field1" (float power-to-grid)
       "field2" (float charge-rate-amps)
       "field3" (float battery-percent)
       "field4" (float power-to-tesla))
      [state
       [(do-after 30 publish-thingspeak-data)]]
      (catch clojure.lang.ExceptionInfo e
        (case (:type (ex-data e))
          :err-could-not-get-tesla-state
          (do
            (log (ex-message e))
            [state
             [(do-after 30 publish-thingspeak-data)]])
          :network-error
          (do
            (log (ex-message e))
            [state
             [(do-after 30 publish-thingspeak-data)]])

          (throw e))))))

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
   (do-now update-tesla-data-loop)
   (do-now login-to-sungrow)
   (do-after 10 regulate-tesla-charge)
   (do-after 10 publish-thingspeak-data)])

(comment
  (let [error-chan (async/chan (async/dropping-buffer 1))
        tesla->regulator (async/chan (async/sliding-buffer 1))
        tesla->logger (async/chan (async/sliding-buffer 1))
        solar->regulator (async/chan (async/sliding-buffer 1))
        solar->logger (async/chan (async/sliding-buffer 1))]
    (async/go
      (update-tesla-data-loop
       tesla->regulator
       tesla->logger
       error-chan))

    (async/go
      (update-solar-data-loop
       solar->regulator
       solar->logger
       error-chan))

    #_(Thread/sleep 10000)
    (let [error (async/<!! error-chan)]
      (printf "Critical error; %s%n" (ex-message error)))
    (async/close! error-chan)
    (async/close! tesla->regulator)
    (async/close! tesla->logger)
    (async/close! solar->regulator)
    (async/close! solar->logger)))

(defn -main
  [& args]
  (let [error-chan (async/chan (async/dropping-buffer 1))
        tesla->regulator (async/chan (async/sliding-buffer 1))
        tesla->logger (async/chan (async/sliding-buffer 1))
        solar->regulator (async/chan (async/sliding-buffer 1))
        solar->logger (async/chan (async/sliding-buffer 1))]

    (async/go
      (update-tesla-data-loop
       tesla->regulator
       tesla->logger
       error-chan))

    #_(Thread/sleep 10000)
    (let [error (async/<!! error-chan)]
      (printf "Critical error; %s%n" (ex-message error)))
    (async/close! error-chan)
    (async/close! tesla->regulator)
    (async/close! tesla->logger)
    (async/close! solar->regulator)
    (async/close! solar->logger)))

#_(defn -main
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

