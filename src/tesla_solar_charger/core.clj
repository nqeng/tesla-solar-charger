(ns tesla-solar-charger.core
  (:gen-class)
  (:require
   [tesla-solar-charger.tesla :as tesla]
   [clj-http.client :as client]
   [tesla-solar-charger.sungrow :as sungrow]
   [tesla-solar-charger.env :as env]
   [clojure.java.io :refer [make-parents]]
   [clojure.string :as str])
  (:import
   (java.time.format DateTimeFormatter)
   (java.time LocalDateTime)
   (java.time.temporal ChronoUnit)))

(defn sleep
  [millis]
  (Thread/sleep millis))

(defn sleep-seconds
  [seconds]
  (Thread/sleep (* 1000 seconds)))

(defn format-time
  [format-str time]
  (.format time (DateTimeFormatter/ofPattern format-str)))

(defn time-now
  []
  (LocalDateTime/now))

(defn replace-symbols
  "Given a map of replacement pairs and a form, returns a (nested)
  form with any symbol = a key in smap replaced with the corresponding
  val in smap."
  [smap form]
  (if (sequential? form)
    (map (partial replace-symbols smap) form)
    (get smap form form)))

(defmacro lazy-let
  "A lazy version of let. It doesn't evaluate any bindings until they
  are needed. No more nested lets and it-lets when you have many
  conditional bindings."
  [bindings & body]
  (let [locals (take-nth 2 bindings)
        local-forms (take-nth 2 (rest bindings))
        smap (zipmap locals (map (fn [local] `(first ~local)) locals))
        bindings (->> (map (fn [lf]
                             `(lazy-seq (list ~(replace-symbols smap lf))))
                           local-forms) (interleave locals) vec)
        body (replace-symbols smap body)]
    (conj body bindings 'let)))

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

(defn did-tesla-stop-charging?
  [was-tesla-charging tesla-state]
  (and
   (not (tesla/is-charging? tesla-state))
   was-tesla-charging))

(defn did-tesla-start-charging?
  [was-tesla-charging tesla-state]
  (and
   (tesla/is-charging? tesla-state)
   (not was-tesla-charging)))

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

;(let [time-now (time-now)
;      tesla-state (tesla/get-vehicle-state)]
;  (do
;    (printf "Time left: %d min%n"
;            (calc-minutes-between-times time-now (get-target-time time-now)))
;    (printf "Charged in: %d min at %dA%n"
;            (tesla/get-minutes-to-full-charge tesla-state)
;            (tesla/get-charge-amps tesla-state))
;    (printf "At max rate: %d min at %dA%n"
;            (tesla/get-minutes-to-full-charge-at-max-rate tesla-state)
;            (tesla/get-max-charge-amps tesla-state))
;    (printf "Optimum rate: %d min at %dA%n"
;            (calc-minutes-between-times time-now (get-target-time time-now))
;            (calc-required-current tesla-state (calc-minutes-between-times time-now (get-target-time time-now)))
;            )))

(defn log-to-thingspeak
  [field-name value]
  (let [field-number (case field-name
                       "grid_power" 1
                       "charge_rate" 2
                       "battery_level" 3
                       "power_draw" 4)]
    (client/get (format "https://api.thingspeak.com/update?api_key=%s&field%d=%f"
                        "XP6ZEG2QSW9J3D2R"
                        field-number
                        (float value)))
    (log (format "Thingspoke %s (%d): %f%n" field-name field-number (float value)))))

(defn run-program
  [state]
  (try
    (lazy-let
     [nothing (println "Lazy let not used.")
      current-time (time-now)
      target-time (get-target-time current-time)
      time-left-minutes (calc-minutes-between-times current-time target-time)
      last-thingspeak-log-time (:last-thingspeak-log-time state)
      time-since-last-thingspeak-log-seconds (seconds-between-times last-thingspeak-log-time current-time)
      thingspeak-logs (:thingspeak-logs state)
      next-thingspeak-log (first thingspeak-logs)
      sungrow-token (if (:sungrow-token state) (:sungrow-token state) (sungrow/login))
      tesla-state (tesla/get-data env/tesla-vin env/tessie-token)
      time-until-charged-minutes (tesla/get-minutes-to-full-charge tesla-state)
      time-until-charged-at-max-rate-minutes (tesla/get-minutes-to-full-charge-at-max-rate tesla-state)
      data-point (sungrow/get-most-recent-data-timestamp (time-now))
      power-to-grid-watts (sungrow/get-power-to-grid sungrow-token data-point)
      tesla-charge-amps (tesla/get-charge-rate tesla-state)
      tesla-max-charge-amps (tesla/get-max-charge-rate tesla-state)
      new-charge-amps (calc-new-charge-rate power-to-grid-watts tesla-charge-amps tesla-max-charge-amps)
      sungrow-status-message (sungrow/create-status-message data-point power-to-grid-watts)
      battery-level (tesla/get-battery-level-percent tesla-state)
      tesla-power-draw (float (* new-charge-amps tesla/power-to-current-3-phase))
      charge-change-amps (- new-charge-amps tesla-charge-amps)]

     (println "new state")

     (cond

       (and (not (empty? thingspeak-logs))
            (or (nil? last-thingspeak-log-time)
                (> time-since-last-thingspeak-log-seconds 30)))
       (do
         (println "thingspeaking...")
         (println thingspeak-logs)
         (println (first next-thingspeak-log))
         (log-to-thingspeak (first next-thingspeak-log) (last next-thingspeak-log))
         (assoc state
                :thingspeak-logs (rest (:thingspeak-logs state))
                :last-thingspeak-log-time current-time))

        ;(not (tesla/is-near-charger? tesla-state))
        ;(do
        ;  (log "Tesla is not at the NQE office")
        ;  (sleep 60000)
        ;  (assoc state
        ;         :was-charging-previously false))

        ; If Tesla is not charging and has been charging previously,
        ; wait
        ;(did-tesla-stop-charging? (:was-charging-previously state) tesla-state)
        ;(do
        ;  (tesla/set-charge-amps tesla-max-charge-amps)
        ;  (log "Tesla disconnected; reset charge amps to max")
        ;  (sleep 10000)
        ;  (assoc state
        ;         :was-charging-previously false))

        ; If Tesla is not charging and hasn't been charging previously,
        ; wait
        ;(not (tesla/is-charging? tesla-state))
        ;(do
        ;  (log "Tesla is not charging")
        ;  (sleep 5000)
        ;  (assoc state
        ;         :was-charging-previously false))

        ; If max charge speed override is in place
       (tesla/is-override-active? tesla-state)
       (do
         (tesla/set-charge-amps tesla-max-charge-amps)
         (log "Charge speed overridden; charging at max"
              (tesla/create-status-message tesla-state))
         (assoc state
                :was-charging-previously true))

        ; If Tesla is charging and hasn't been charging previously,
        ; begin charging at zero amps
       (did-tesla-start-charging? (:was-charging-previously state) tesla-state)
       (do
         (tesla/set-charge-amps 0)
         (log "Tesla connected; started charging at 0A"
              (tesla/create-status-message tesla-state))
         (assoc state
                :was-charging-previously true
                ;:thingspeak-logs (conj (:thingspeak-logs state)
                ;                       ["charge_rate" 0]
                ;                       ["battery_level" (first battery-level)]
                ;                       ["power_draw" 0])
                ))

        ; If no new data point
       (= data-point (:last-data-point state))
       (do
         (assoc state
                :was-charging-previously true))

        ; If data point hasn't been published yet, wait
       (nil? power-to-grid-watts)
       (do
         (log "Null data point; waiting for data")
         (sleep 40000)
         (assoc state
                :was-charging-previously true
                :sungrow-token sungrow-token))

        ; If charge amps haven't changed, don't update Tesla
       (= new-charge-amps tesla-charge-amps)
       (do
         (println "conjoining...")
         (println power-to-grid-watts)
         (conj (:thingspeak-logs state) [""])
         (log
          "No change to Tesla charge speed"
          sungrow-status-message
          (tesla/create-status-message tesla-state)
          (format "Time left: %d minutes%n" time-left-minutes))
         (assoc state
                :was-charging-previously true
                :sungrow-token sungrow-token
                :last-data-point data-point
                ;:thingspeak-logs (conj (:thingspeak-logs state)
                ;                       ["grid_power" (first power-to-grid-watts)]
                ;                       ["charge_rate" (first new-charge-amps)]
                ;                       ["battery_level" (first battery-level)]
                ;                       ["power_draw" (first tesla-power-draw)])
                ))

; If charge amps have changed, update Tesla
       (<= time-left-minutes time-until-charged-at-max-rate-minutes)
       (do
         (tesla/set-charge-amps tesla-max-charge-amps)
         (log "Charge speed overridden; charging at max"
              (tesla/create-status-message tesla-state))
         (assoc state
                :was-charging-previously true
               ; :thingspeak-logs (conj (:thingspeak-logs state)
               ;                        ["grid_power" power-to-grid-watts]
               ;                        ["charge_rate" new-charge-amps]
               ;                        ["battery_level" battery-level]
               ;                        ["power_draw" tesla-power-draw])
                ))

       :else
       (do
         (tesla/set-charge-amps new-charge-amps)
         (log (format "Changed Tesla charge speed by %s%dA"
                      (if (pos? charge-change-amps) "+" "-")
                      (abs charge-change-amps))
              sungrow-status-message
              (tesla/create-status-message tesla-state))
         (assoc state
                :was-charging-previously true
                :sungrow-token sungrow-token
                :last-data-point data-point
                ;:thingspeak-logs (conj (:thingspeak-logs state)
                ;                       ["grid_power" power-to-grid-watts]
                ;                       ["charge_rate" new-charge-amps]
                ;                       ["battery_level" battery-level]
                ;                       ["power_draw" tesla-power-draw])
                ))))

    (catch java.net.UnknownHostException e
      (do
        (log (str "Network error; " (.getMessage e)))
        (sleep 10000)
        state))
    (catch java.net.NoRouteToHostException e
      (do
        (log (str "Network error; " (.getMessage e)))
        (sleep 10000)
        state))
    (catch clojure.lang.ExceptionInfo e
      (case (:type (ex-data e))
        :err-sungrow-auth-failed
        (do
          (log "Sungrow not logged in; re-authenticated")
          (assoc state
                 :was-charging-previously true
                 :sungrow-token nil))

        :err-sungrow-login-too-frequently
        (do
          (log (ex-message e))
          (sleep 10000)
          (assoc state
                 :was-charging-previously true))

        (throw e)))))

(def initial-state {:sungrow-token nil
                    :last-data-point nil
                    :was-charging-previously false
                    :thingspeak-logs []
                    :last-thingspeak-log-time nil})

(defn pop-action
  [state]
  [(assoc state :actions (vec (rest (:actions state)))) (first (:actions state))])

(defn push-action
  [state action]
  (assoc state :actions (conj (:actions state) action)))

(defn fn-name
  [f]
  (clojure.string/replace (first (re-find #"(?<=\$)([^@]+)(?=@)" (str f))) "_" "-"))

(defn print-state
  [state]
  (println (format "{%n\t:number %d, %n\t:actions %s %n\t:tesla-data %d chars%n}"
                   (:number state)
                   (vec (map fn-name (:actions state)))
                   (count (str (:tesla-data state)))))
  state)

(defn log-message
  [message]
  (println message))

(defn start-sleep
  [seconds state]
  (assoc state :sleep-until (.plusSeconds (:time state) seconds)))

(defn check-if-tesla-charge-rate-changed
  [state]
  (if (not (= (:new-tesla-charge-rate state) (tesla/get-charge-rate (:tesla-data state))))
    (-> state
        (push-action (fn [state] (tesla/set-charge-amps (:new-tesla-charge-rate state)) state))
        (push-action (fn [state] (printf "Set tesla charge rate to %dA%n" (:new-tesla-charge-rate state)) state)))
    (-> state
        (push-action (fn [state] (printf "Tesla charge rate same; no change%n") state)))))

(defn calc-tesla-charge-rate
  [state]
  (-> (:power-to-grid state)
      (- env/power-buffer-watts)
      (limit (- env/max-drop-amps) (env/max-climb-amps))
      (+ (tesla/get-charge-rate (:tesla-data state)))
      float
      Math/round
      int
      (limit 0 (tesla/get-max-charge-rate (:tesla-data state)))
      (assoc state :new-tesla-charge-rate)))

(defn check-if-grid-power-is-nil
  [state]
  (if (nil? (:power-to-grid state))
    (-> state
        (push-action (fn [state] (printf "Null data point; waiting for data%n") state)))
    (-> state
        (push-action calc-tesla-charge-rate)
        (push-action check-if-tesla-charge-rate-changed))))

(defn login-to-sungrow
  [state]
  (try
    (-> state
        (assoc :sungrow-token (sungrow/login))
        (push-action (fn [state] (printf "Sungrow not logged in; re-authenticated%n") state)))
    (catch clojure.lang.ExceptionInfo e
      (case (:type (ex-data e))
        (assoc state :sungrow-token nil)))))

(defn get-sungrow-data
  [state]
  (try
    (-> state
        (assoc :power-to-grid (sungrow/get-power-to-grid (:sungrow-token state) (:next-data-point state)))
        (push-action check-if-grid-power-is-nil))
    (catch clojure.lang.ExceptionInfo e
      (case (:type (ex-data e))
        :err-sungrow-auth-failed
        (-> state
            (push-action login-to-sungrow))
        (throw e)))))

(defn check-if-new-sungrow-data
  [state]
  (if (not (= (:next-data-point state) (:last-data-point state)))
    (-> state
        (push-action get-sungrow-data))
    state))

(defn get-next-sungrow-data-point
  [state]
  (-> state
      (assoc :next-data-point (sungrow/create-data-point-timestamp (:time state)))))

(defn get-time-left
  [state]
  (-> state
      (assoc :time-left (calc-minutes-between-times (:time state) (get-target-time (:time state))))))

(defn set-tesla-charge-rate
  [new-charge-rate state]
  (if (= new-charge-rate (tesla/get-charge-rate (:tesla-data state)))
    (-> state
        (push-action (fn [state] (tesla/set-charge-amps new-charge-rate) state))
        (push-action (fn [state] (printf "Set tesla charge rate to %dA%n" new-charge-rate) state))
        )
    (-> state
        (push-action (fn [state] (printf "No change to Tesla charge rate%n") state))
        )))

(defn set-tesla-charge-rate-to-max
  [state]
  (let [max-charge-rate (tesla/get-max-charge-rate (:tesla-data state))]
    (-> state
        (push-action (partial set-tesla-charge-rate max-charge-rate)))))

(defn check-if-should-override
  [state]
  (if (<= (:time-left state) (tesla/get-minutes-to-full-charge-at-max-rate (:tesla-data state)))
    (-> state
        (push-action (fn [state] (printf "Overriding charge speed to get to full%n" state)))
        (push-action set-tesla-charge-rate-to-max))
    (-> state
        (push-action get-next-sungrow-data-point)
        (push-action check-if-new-sungrow-data))))

(defn check-if-user-override-active
  [state]
  (if (tesla/is-in-valet-mode? (:tesla-data state))
    (-> state
        (push-action (fn [state] (printf "Charge speed override active%n" state)))
        (push-action set-tesla-charge-rate-to-max))
    (-> state
        (push-action get-time-left)
        (push-action check-if-should-override))))

(defn check-if-tesla-just-connected
  [state]
  (if (not (:was-charging-previously state))
    (-> state
        (push-action (partial set-tesla-charge-rate 0))
        (push-action (fn [state] (printf "Tesla connected%n") state)))
    (-> state
        (push-action check-if-user-override-active))))

(defn check-if-tesla-just-disconnected
  [state]
  (if (:was-charging-previously state)
    (-> state
        (push-action set-tesla-charge-rate-to-max)
        (push-action (fn [state] (printf "Tesla disconnected%n") state))
        (push-action (partial start-sleep 5)))
    (-> state
        (push-action (fn [state] (printf "Tesla is not charging%n") state))
        (push-action (partial start-sleep 5)))))

(defn check-if-tesla-is-charging
  [state]
  (if (tesla/is-charging? (:tesla-data state))
    (-> state
        (push-action check-if-tesla-just-connected)
        (assoc :was-charging-previously true))
    (-> state
        (push-action check-if-tesla-just-disconnected)
        (assoc :was-charging-previously false))))

(defn check-if-tesla-is-near-charger
  [state]
  (if (tesla/is-near-charger? (:tesla-data state))
    (-> state
        (push-action check-if-tesla-is-charging))
    (-> state
        (push-action (fn [state] (printf "Tesla is not near the charger%n") state))
        (push-action (partial start-sleep 60))
        (assoc :was-charging-previously false))))

(defn get-tesla-data
  [state]
  (try
    (-> state
        (assoc :tesla-data (tesla/get-data))
        (push-action check-if-tesla-is-near-charger))
    (catch clojure.lang.ExceptionInfo e
      (case (:type (ex-data e))
        :err-could-not-get-tesla-state
        (-> state
            (push-action (fn [state] (printf "Could not get tesla state%n") state))
            (push-action (partial start-sleep 10))
            (assoc :was-charging-previously false))
        (throw e)))))

(defn is-delaying-thingspeak?
  [state]
  (and (not (nil? (:delay-thingspeak-until state)))
       (.isBefore (:time state) (:delay-thingspeak-until state))))

(defn log-to-thingspeak
  [& data]
  (let [field-names (take-nth 2 data)
        field-values (take-nth 2 (rest data))
        url (apply str
                   "https://api.thingspeak.com/update"
                   "?"
                   "api_key="
                   "XP6ZEG2QSW9J3D2R"
                   "&"
                   (interpose "&" (map #(str %1 "=" %2) field-names field-values)))]
    (client/get url)
    ))

; todo: imaginary keywords

(defn log-next-thingspeak
  [state]
  (if (is-delaying-thingspeak? state)
    state
    (let [power-to-grid (:last-power-to-grid state)
          charge-rate-amps (:new-charge-rate-amps state)
          battery-percent (tesla/get-battery-level-percent (:tesla-data state))
          power-to-tesla (:new-charge-rate-amps state)]
      (log-to-thingspeak
       "field1" (float power-to-grid)
       "field2" (float charge-rate-amps)
       "field3" (float battery-percent)
       "field4" (float power-to-tesla))
      state)))

(defn is-sleeping?
  [state]
  (and (not (nil? (:sleep-until state)))
       (.isBefore (:time state) (:sleep-until state))))

(defn perform-next-action
  [state]
  (if (is-sleeping? state)
    state
    (if (empty? (:actions state))
      (push-action state get-tesla-data)
      (let [[state next-action] (pop-action state)]
        (println (str "performing " (fn-name next-action)))
        (next-action state)))))

(defn act
  [state]
  (-> state
      (assoc :time (time-now))
      log-next-thingspeak
      perform-next-action))

(defn -main
  [& args]
  (println "Starting...")

  (loop [state {}]
    (let [new-state (act state)]
      (recur new-state))))

;(defn -main
;  [& args]
;  (log "Starting...")
;  (loop [state initial-state]
;    (let [new-state (run-program state)]
;      (recur new-state))))

