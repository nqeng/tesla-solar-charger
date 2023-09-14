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

(defn fn-name
  [f]
  (clojure.string/replace (first (re-find #"(?<=\$)([^@]+)(?=@)" (str f))) "_" "-"))

(defn get-time-left-minutes
  []
  (calc-minutes-between-times (time-now) (get-target-time (time-now))))

(defn get-excess-power
  [state]
  (-> state
      :solar-data
      last
      (get "1152381_7_2_3")
      (get "p8018")))

(defn update-tesla-data
  [tesla->regulator tesla->logger]
  (let [result {}]
    (try
      (let [tesla-data
            #_(tesla/get-data) (json/parse-string (slurp "test.json"))
            delay (cond
                    (not (tesla/is-near-charger? tesla-data))
                    60000

                    (not (tesla/is-charging? tesla-data))
                    30000

                    :else
                    10000)
            result (-> result
                       (assoc :tesla-state tesla-data)
                       (assoc :delay delay))]

        (cond
          (false? (async/>!! tesla->regulator tesla-data))
          (throw (ex-info "Channel tesla->regulator closed!" {}))

          (false? (async/>!! tesla->logger tesla-data))
          (throw (ex-info "Channel tesla->logger closed!" {}))

          :else
          (-> result
              (assoc :message (format "Send Tesla data: {t=%s, %s keys} -> channel" (get-in tesla-data ["charge_state" "timestamp"]) (count tesla-data))))))

      (catch clojure.lang.ExceptionInfo e
        (case (:type (ex-data e))

          (throw e)))
      (catch Exception e
        (throw e)))))

(defn update-tesla-data-loop
  [tesla->regulator tesla->logger error-chan]
  (try
    (loop [last-result nil]
      (log :verbose "[Tesla] Updating Tesla state")
      (let [result (update-tesla-data tesla->regulator tesla->logger)]
        (when (not (nil? (:message result)))
          (log :info (format "[Tesla] %s%n" (:message result))))

        (when (not (nil? (:delay result)))
          (log :info (format "[Tesla] Waiting for %ss%n" (int (/ (:delay result) 1000))))
          (Thread/sleep (:delay result)))

        (recur result)))
    (catch clojure.lang.ExceptionInfo e
      (async/>!! error-chan e))
    (catch Exception e
      (async/>!! error-chan e))))

(defn update-solar-data
  [solar->regulator solar->logger sungrow-token]
  (let [result {:sungrow-token sungrow-token}]

    (if (nil? sungrow-token)
      (try
        (let [sungrow-token (sungrow/login)]
          (-> result
              (assoc :message (format "Logged in to Sungrow: token %s" sungrow-token))
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
              _ (log :info (format "[Solar] Next data point is at %s" next-data-publish-time))
              millis-until-next-data (-> (millis-between-times current-time next-data-publish-time)
                                         (+ 40000))]
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
                (assoc :message (format "Sent Sungrow data %s -> channel" solar-data))
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
      (log :verbose "[Solar] Updating solar data")
      (let [sungrow-token (:sungrow-token last-result)
            result (update-solar-data solar->regulator solar->logger sungrow-token)]
        (when (not (nil? (:message result)))
          (log :info (format "[Solar] %s" (:message result))))

        (when (not (nil? (:delay result)))
          (log :info (format "[Solar] Waiting for %ss%n" (int (/ (:delay result) 1000))))
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
      (let [[value channel] (async/alts!! [tesla->regulator solar->regulator])]

        (cond
          (nil? value)
          (throw (ex-info "Channel closed!" {}))

          (= channel solar->regulator)
          (-> result
              (assoc :solar-data value)
              (assoc :delay nil)
              (assoc :message (format "Received solar data: %s <- channel" value)))

          :else
          (let [tesla-data value
                regulation {:solar-data solar-data
                            :tesla-data tesla-data}]
            (log :info (format "[Regulator] Received Tesla data: {t=%s, %s keys} <- channel" (get-in tesla-data ["charge_state" "timestamp"]) (count tesla-data)))
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

              (nil? solar-data)
              (-> result
                  (assoc :message "No solar data")
                  )

              (nil? (get-excess-power solar-data))
              (-> result
                  (assoc :message "No excess power"))

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
      (log :verbose "[Regulator] Regulating tesla data")
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
          (log :info (format "[Regulator] Waiting for %ss%n" (int (/ (:delay result) 1000))))
          (Thread/sleep (:delay result)))

        (recur result last-regulation first-regulation solar-data)))
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

(defn -main
  [& args]
  (let [error-chan (async/chan (async/dropping-buffer 1))
        tesla->regulator (async/chan (async/sliding-buffer 1))
        tesla->logger (async/chan (async/sliding-buffer 1))
        solar->regulator (async/chan (async/sliding-buffer 1))
        solar->logger (async/chan (async/sliding-buffer 1))]

    (async/thread
      (update-tesla-data-loop
       tesla->regulator
       tesla->logger
       error-chan))

    (async/thread
      (update-solar-data-loop
       solar->regulator
       solar->logger
       error-chan))

    (async/thread
      (regulate-tesla-charge-loop
       tesla->regulator
       solar->regulator
       error-chan))

    #_(Thread/sleep 10000)
    (let [e (async/<!! error-chan)]
      (when (not (nil? e))
        (printf "Critical error; %s%n" (ex-message e))
        (client/post "https://ntfy.sh/github-nqeng-tesla-solar-charger" {:body (ex-message e)})))

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

