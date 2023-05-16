(ns sungrow-tesla.core
  (:gen-class)
  (:require
   [dotenv :refer [env]]
   [sungrow-tesla.tesla :as tesla]
   [sungrow-tesla.sungrow :as sungrow]))

(def power-to-current-3-phase 687.5)
(def power-to-current-3-phase-delta 262.5)
(def power-to-current-1-phase 231.25)
(def power-to-current-2-phase 462.5)

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

(defn time-now
  []
  (java.time.LocalDateTime/now))

(defn has-tesla-been-charging?
  "If a data point has been processed, the Tesla has been charging."
  [program-state]
  (not (nil? (:last-data-point-timestamp program-state))))

(defn limit
  [num min max]
  (cond
    (> num max) max
    (< num min) min
    :else       num))

(defn calc-new-charge-amps
  ([power-to-grid-watts
    tesla-charge-amps
    tesla-max-amps
    power-buffer-watts
    max-climb-amps
    max-drop-amps]
   (let [available-power (- power-to-grid-watts power-buffer-watts)
         adjustment-amps (-> (/ available-power power-to-current-3-phase)
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
   (calc-new-charge-amps power-to-grid-watts
                         tesla-charge-amps
                         tesla-max-amps
                         (Float/parseFloat (env "POWER_BUFFER_WATTS"))
                         (Float/parseFloat (env "MAX_CLIMB_AMPS"))
                         (Float/parseFloat (env "MAX_DROP_AMPS")))))

(defn tesla-just-disconnected?
  [program-state tesla-state]
  (and (not (tesla/is-tesla-charging? tesla-state))
       (has-tesla-been-charging? program-state)))

(defn tesla-just-connected?
  [program-state tesla-state]
  (and (tesla/is-tesla-charging? tesla-state)
       (not (has-tesla-been-charging? program-state))))

(defn create-status-message
  [latest-data-point
   power-to-grid-watts
   new-charge-amps
   current-charge-amps
   battery-level
   power-buffer-watts]
  (format "
Data point timestamp: %s
Power feeding to grid: %.2fW
Power available to Tesla: %.2fW
Tesla charge speed: %dA (%s%dA)
Tesla power draw: %.2fW
Battery level: %d%%"
          latest-data-point
          power-to-grid-watts
          (- power-to-grid-watts power-buffer-watts)
          new-charge-amps
          (if (pos? (- new-charge-amps current-charge-amps)) "+" "-")
          (abs (- new-charge-amps current-charge-amps))
          (* new-charge-amps power-to-current-3-phase)
          battery-level))

(defn is-charge-overridden?
  [tesla-state]
  (tesla/is-tesla-in-valet-mode? tesla-state))

(defn run-program
  "Executes actions based on current program state, returning the new state"
  [state]
  (try
    (lazy-let
     [data-timestamp (sungrow/get-most-recent-data-timestamp (:time state))
      tesla-state (tesla/request-vehicle-state)
      tesla-charge-amps (tesla/get-charge-amps tesla-state)
      tesla-max-amps (tesla/get-max-amps tesla-state)
      tesla-battery-level (tesla/get-battery-level tesla-state)
      power-to-grid-watts (sungrow/get-power-to-grid (:sungrow-token state) data-timestamp)
      new-charge-amps (calc-new-charge-amps power-to-grid-watts tesla-charge-amps tesla-max-amps)]
     (cond
       (not (tesla/is-tesla-near-charger? tesla-state))
       (assoc state
              :message "Tesla is not at the NQE office"
              :delay 60000)
        ; If Tesla is not charging and has been charging previously,
        ; wait
       (tesla-just-disconnected? state tesla-state)
       (assoc state
              :message "Tesla disconnected; reset charge amps to max"
              :delay 10000
              :last-data-point-timestamp nil
              :new-charge-amps (tesla/get-max-amps tesla-state))
        ; If Tesla is not charging and hasn't been charging previously,
        ; wait
       (not (tesla/is-tesla-charging? tesla-state))
       (assoc state
              :message "Tesla is not charging"
              :delay 5000)
        ; If max charge speed override is in place
       (is-charge-overridden? tesla-state)
       (assoc state
              :message (str "Charge speed overridden; charging at " tesla-max-amps "A")
              :last-data-point-timestamp data-timestamp
              :new-charge-amps tesla-max-amps)
        ; If Tesla is charging and hasn't been charging previously,
        ; begin charging at zero amps
       (tesla-just-connected? state tesla-state)
       (assoc state
              :message "Tesla connected; started charging at 0A"
              :last-data-point-timestamp data-timestamp
              :new-charge-amps 0)
        ; If it's not the time to receive a fresh data point, wait
       (not (sungrow/has-fresh-data-point? (:time state) (:last-data-point-timestamp state)))
       (assoc state
              :message nil)
        ; If data point hasn't been populated yet, wait
       (nil? power-to-grid-watts)
       (assoc state
              :message "Null data point; waiting for data"
              :delay 40000)
        ; If charge amps haven't changed, don't update Tesla
       (= new-charge-amps tesla-charge-amps)
       (assoc state
              :message (create-status-message
                        data-timestamp
                        power-to-grid-watts
                        new-charge-amps
                        tesla-charge-amps
                        tesla-battery-level
                        (Float/parseFloat (env "POWER_BUFFER_WATTS")))
              :last-data-point-timestamp data-timestamp)
        ; All good
       :else
       (assoc state
              :message (create-status-message
                        data-timestamp
                        power-to-grid-watts
                        new-charge-amps
                        tesla-charge-amps
                        tesla-battery-level
                        (Float/parseFloat (env "POWER_BUFFER_WATTS")))
              :last-data-point-timestamp data-timestamp
              :new-charge-amps new-charge-amps)))
    (catch clojure.lang.ExceptionInfo e
      (case (:type (ex-data e))
        :err-could-not-get-sungrow-data (assoc state :message (ex-message e) :sungrow-token nil)
        :err-could-not-get-tesla-state (assoc state :message (ex-message e))
        :err-could-not-login-to-sungrow (throw e)
        :err-too-many-failed-login-attempts (throw e)
        :err-logging-in-too-frequently (assoc state :message (ex-message e) :delay 60000)
        (throw e)))))

(def initial-state {:time (time-now)
                    :last-data-point-timestamp nil
                    :delay nil
                    :new-charge-amps nil
                    :sungrow-token (sungrow/login)})

(defn log
  [message]
  (println
   (format "[%s] %s"
           (.format
             (time-now)
            (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss"))
           message)))

(defn -main
  [& args]
  (println "Starting...")
  (loop [state initial-state]
    (let [new-state       (run-program state)
          sungrow-token   (:sungrow-token new-state)
          message         (:message new-state)
          new-charge-amps (:new-charge-amps new-state)
          delay           (:delay new-state)
          next-state      (assoc new-state
                                 :time (time-now)
                                 :message nil
                                 :delay nil
                                 :new-charge-amps nil
                                 :sungrow-token (if sungrow-token
                                                  sungrow-token
                                                  (sungrow/login)))]
      (try
        (if new-charge-amps
          (tesla/update-charge-amps new-charge-amps))
        (if message
          (log message))
        (if delay
          (Thread/sleep delay))
        (catch clojure.lang.ExceptionInfo e
          (println (ex-message e))))

      (recur next-state))))



