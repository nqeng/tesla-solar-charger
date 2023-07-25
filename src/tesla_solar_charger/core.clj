(ns tesla-solar-charger.core
  (:gen-class)
  (:require
   [tesla-solar-charger.tesla :as tesla]
   [tesla-solar-charger.sungrow :as sungrow]
   [tesla-solar-charger.env :as env]
   [clojure.java.io :refer [make-parents]]
   [clojure.string :as str])
  (:import
    (java.time.format DateTimeFormatter)
    (java.time LocalDateTime)))

(defn sleep
  [millis]
  (Thread/sleep millis))

(defn format-time
  [format-str time]
  (.format time (DateTimeFormatter/ofPattern format-str))
  )

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
    (spit log-file-path (str log-message "\n") :append true)
    ))

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
   (calc-new-charge-amps
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

(defn run-program
  [state]
  (try
    (lazy-let
     [sungrow-token (if (:sungrow-token state) (:sungrow-token state) (sungrow/login))
      tesla-state (tesla/get-vehicle-state)
      data-point (sungrow/get-most-recent-data-timestamp (time-now))
      power-to-grid-watts (sungrow/get-power-to-grid sungrow-token data-point)
      tesla-charge-amps (tesla/get-charge-amps tesla-state)
      tesla-max-charge-amps (tesla/get-max-charge-amps tesla-state)
      new-charge-amps (calc-new-charge-amps power-to-grid-watts tesla-charge-amps tesla-max-charge-amps)
      sungrow-status-message (sungrow/create-status-message data-point power-to-grid-watts)
      charge-change-amps (- new-charge-amps tesla-charge-amps)]
      (cond
        (not (tesla/is-near-charger? tesla-state))
        (do
          (log "Tesla is not at the NQE office")
          (sleep 60000)
          (assoc state
                 :was-tesla-charging false))
                ; If Tesla is not charging and has been charging previously,
                ; wait
        (did-tesla-stop-charging? (:was-tesla-charging state) tesla-state)
        (do
          (tesla/set-charge-amps tesla-max-charge-amps)
          (log "Tesla disconnected; reset charge amps to max")
          (sleep 10000)
          (assoc state
                 :was-tesla-charging false))
        ; If Tesla is not charging and hasn't been charging previously,
        ; wait
        (not (tesla/is-charging? tesla-state))
        (do
          (log "Tesla is not charging")
          (sleep 5000)
          (assoc state
                 :was-tesla-charging true))
        ; If max charge speed override is in place
        (tesla/is-charge-overridden? tesla-state)
        (do
          (tesla/set-charge-amps tesla-max-charge-amps)
          (log "Charge speed overridden; charging at max" 
               (tesla/create-status-message tesla-state))
          (assoc state
                 :was-tesla-charging true))
        ; If Tesla is charging and hasn't been charging previously,
        ; begin charging at zero amps
        (did-tesla-start-charging? (:was-tesla-charging state) tesla-state)
        (do
          (tesla/set-charge-amps 0)
          (log "Tesla connected; started charging at 0A" 
               (tesla/create-status-message tesla-state))
          (assoc state
                 :was-tesla-charging true))
        ; If no new data point
        (= data-point (:last-data-point state))
        (do
          (assoc state
                 :was-tesla-charging true))
        ; If data point hasn't been published yet, wait
        (nil? power-to-grid-watts)
        (do
          (log "Null data point; waiting for data")
          (sleep 40000)
          (assoc state
                 :was-tesla-charging true
                 :sungrow-token sungrow-token))
        ; If charge amps haven't changed, don't update Tesla
        (= new-charge-amps tesla-charge-amps)
        (do
          (log 
            "No change to Tesla charge speed" 
            sungrow-status-message 
            (tesla/create-status-message tesla-state))
          (assoc state
                 :was-tesla-charging true
                 :sungrow-token sungrow-token
                 :last-data-point data-point))
        ; If charge amps have changed, update Tesla
        :else
        (do
          (tesla/set-charge-amps new-charge-amps)
          (log (format "Changed Tesla charge speed by %s%dA"
                       (if (pos? charge-change-amps) "+" "-")
                       (abs charge-change-amps))
               sungrow-status-message
               (tesla/create-status-message tesla-state))
          (assoc state
                 :was-tesla-charging true
                 :sungrow-token sungrow-token
                 :last-data-point data-point))))
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
        :err-sungrow-auth-failed (do
                                   (log "Sungrow not logged in; re-authenticated")
                                   (assoc state
                                          :was-tesla-charging true
                                          :sungrow-token nil))
        :err-sungrow-login-too-frequently (do
                                            (log (ex-message e))
                                            (sleep 10000)
                                            (assoc state
                                                   :was-tesla-charging true))
        (throw e)))))

(def initial-state {:sungrow-token nil
                    :last-data-point nil
                    :was-tesla-charging false})

(defn -main
  [& args]
  (log "Starting...")
  (loop [state initial-state]
    (let [new-state (run-program state)]
      (recur new-state))))

