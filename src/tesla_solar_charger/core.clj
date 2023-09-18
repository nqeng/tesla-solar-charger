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

(defn time-after-seconds
  [seconds]
  (.plusSeconds (time-now) seconds))

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

(defn get-time-left-minutes
  []
  (calc-minutes-between-times (time-now) (get-target-time (time-now))))

(defn update-tesla-data
  [tesla->regulator tesla->logger log-chan]
  (let [result {}]
    (try
      (let [tesla-data
            #_(tesla/get-data) (json/parse-string (slurp "test.json"))
            delay (cond
                    (not (tesla/is-near-charger? tesla-data))
                    60

                    (not (tesla/is-charging? tesla-data))
                    30

                    :else
                    10)
            result (-> result
                       (assoc :tesla-state tesla-data)
                       (assoc :delay-until (time-after-seconds delay)))]

        (cond
          (false? (async/>!! tesla->regulator tesla-data))
          (throw (ex-info "Channel tesla->regulator closed!" {}))

          (false? (async/>!! tesla->logger tesla-data))
          (throw (ex-info "Channel tesla->logger closed!" {}))

          :else
          (do
            (async/>!! log-chan (format "[Tesla] Send Tesla data: {t=%s, %s keys} -> channel"
                                        (get-in tesla-data ["charge_state" "timestamp"])
                                        (count tesla-data)))
            result)))

      (catch clojure.lang.ExceptionInfo e
        (case (:type (ex-data e))

          (throw e)))
      (catch Exception e
        (throw e)))))

(defn update-tesla-data-loop
  [tesla->regulator tesla->logger log-chan error-chan]
  (try
    (loop [last-result nil]
      (async/>!! log-chan "[Tesla] Working...")
      (let [result (update-tesla-data tesla->regulator tesla->logger log-chan)]

        (when-some [delay-until (:delay-until result)]
          (when (.isAfter delay-until (time-now))
            (async/>!! log-chan (format "[Tesla] Next run in %ss (%s)"
                                        (seconds-between-times (time-now) delay-until)
                                        delay-until))
            (Thread/sleep (millis-between-times (time-now) delay-until))))

        (recur result)))
    (catch clojure.lang.ExceptionInfo e
      (async/>!! error-chan e))
    (catch Exception e
      (async/>!! error-chan e))))

(defn update-solar-data
  [solar->regulator solar->logger log-chan sungrow-token]
  (let [result {:sungrow-token sungrow-token}]

    (if (nil? sungrow-token)
      (try
        (let [sungrow-token (sungrow/login)]
          (do
            (async/>!! log-chan (format "[Solar] Logged in to Sungrow: token %s" sungrow-token))
            (-> result
                (assoc :sungrow-token sungrow-token))))
        (catch clojure.lang.ExceptionInfo e
          (case (:type (ex-data e))

            :err-sungrow-login-too-frequent
            (do
              (async/>!! log-chan (ex-message e))
              (-> result
                  (assoc :delay-until (time-after-seconds 60))))

            (throw e)))
        (catch Exception e
          (throw e)))

      (try
        (let [current-time (time-now)
              power-to-grid-watts (sungrow/get-power-to-grid sungrow-token current-time)
              solar-data [{:timestamp (time-now) :power-to-grid-watts power-to-grid-watts}]
              result (assoc result :solar-data solar-data)
              next-data-publish-time (.plusSeconds (sungrow/get-next-data-publish-time current-time) 40)]
          (cond
            (nil? solar-data)
            (do
              (async/>!! log-chan "[Solar] data not published yet")
              (-> result
                  (assoc :delay-until (time-after-seconds 40))))

            (false? (async/>!! solar->regulator solar-data))
            (throw (ex-info "Channel solar->regulator closed!" {}))

            (false? (async/>!! solar->logger solar-data))
            (throw (ex-info "Channel solar->logger closed!" {}))

            :else
            (do
              (async/>!! log-chan (format "[Solar] Sent solar data: {t=%s, %s keys} -> channel"
                                          (:timestamp (last solar-data))
                                          (count (last solar-data))))
              (async/>!! log-chan (format "[Solar] Next data point is at %s" next-data-publish-time))
              (-> result
                  (assoc :delay-until next-data-publish-time)))))
        (catch clojure.lang.ExceptionInfo e
          (case (:type (ex-data e))

            :err-sungrow-auth-failed
            (do
              (async/>!! log-chan "[Solar] Sungrow not logged in")
              (-> result
                  (assoc :sungrow-token nil)))

            (throw e)))
        (catch Exception e
          (throw e))))))

(defn update-solar-data-loop
  [solar->regulator solar->logger log-chan error-chan]
  (try
    (loop [last-result nil]
      (async/>!! log-chan "[Solar] Working...")
      (let [sungrow-token (:sungrow-token last-result)
            result (update-solar-data solar->regulator solar->logger log-chan sungrow-token)]

        (when-some [delay-until (:delay-until result)]
          (when (.isAfter delay-until (time-now))
            (async/>!! log-chan (format "[Solar] Next run in %ss (%s)"
                                        (seconds-between-times (time-now) delay-until)
                                        delay-until))
            (Thread/sleep (millis-between-times (time-now) delay-until))))

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
  [tesla->regulator solar->regulator log-chan last-regulation first-regulation solar-data]
  (let [result {:delay-until (time-after-seconds 10)}]
    (try
      (let [[value channel] (async/alts!! [tesla->regulator solar->regulator])]

        (cond
          (nil? value)
          (throw (ex-info "Channel closed!" {}))

          (= channel solar->regulator)
          (do
            (async/>!! log-chan (format "[Regulator] Received solar data: {t=%s, %s keys} <- channel" (:timestamp (last value)) (count (last value))))
            (-> result
                (assoc :solar-data value)
                (assoc :delay-until nil)))

          :else
          (let [tesla-data value
                regulation {:solar-data solar-data
                            :tesla-data tesla-data}]
            (async/>!! log-chan (format "[Regulator] Received Tesla data: {t=%s, %s keys} <- channel"
                                        (get-in tesla-data ["charge_state" "timestamp"])
                                        (count tesla-data)))
            (cond
              (did-tesla-start-charging? tesla-data (:tesla-data last-regulation))
              (let [starting-rate-amps 0]
                (tesla/set-charge-limit env/target-percent)
                (tesla/set-charge-rate starting-rate-amps)
                (async/>!! log-chan (format "[Regulator] Tesla connected; set charge rate to %sA" starting-rate-amps))
                (-> result
                    (assoc :regulation (-> regulation
                                           (assoc :new-charge-rate-amps starting-rate-amps)
                                           (assoc :new-charge-limit-percent 0)))
                    (assoc :started-regulating true)))

              (did-tesla-stop-charging? tesla-data (:tesla-data last-regulation))
              (do
                (tesla/set-charge-rate (tesla/get-charge-rate (:tesla-data first-regulation)))
                (tesla/set-charge-limit (tesla/get-charge-limit (:tesla-data first-regulation)))
                (async/>!! log-chan "[Regulator] Reset charge amps to before")
                (-> result
                    (assoc :regulation (-> regulation
                                           (assoc :new-charge-rate-amps 0)
                                           (assoc :new-charge-limit-percent 0)))))

              (not (tesla/is-near-charger? tesla-data))
              (do
                (async/>!! log-chan "[Regulator] Tesla is not near charger")
                result)

              (not (tesla/is-charging? tesla-data))
              (do
                (async/>!! log-chan "[Regulator] Tesla is not charging")
                result)

              (tesla/is-override-active? tesla-data)
              (let [max-rate-amps (tesla/get-max-charge-rate tesla-data)]
                (tesla/set-charge-rate max-rate-amps)
                (async/>!! log-chan (format "[Regulator] User override active; set charge rate to max (%sA)" max-rate-amps))
                (-> result
                    (assoc :regulation (-> regulation
                                           (assoc :new-charge-rate-amps 0)))))

              (<= (get-time-left-minutes) (tesla/get-minutes-to-full-charge-at-max-rate tesla-data))
              (let [max-rate-amps (tesla/get-max-charge-rate tesla-data)]
                (tesla/set-charge-rate max-rate-amps)
                (async/>!! log-chan (format "[Regulator] Auto override active; set charge rate to max (%sA)" max-rate-amps))
                (-> result
                    (assoc :regulation (-> regulation
                                           (assoc :new-charge-rate-amps 0)))))

              (has-regulated-this-solar-data? solar-data last-regulation)
              (do
                (async/>!! log-chan "[Regulator] Already processed this solar data")
                result)

              (nil? solar-data)
              (do
                (async/>!! log-chan "[Regulator] No solar data")
                result)

              (nil? (:power-to-grid-watts (last solar-data)))
              (do
                (async/>!! log-chan "[Regulator] No excess power")
                result)

              :else
              (let [excess-power (:power-to-grid-watts (last solar-data))
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
                (async/>!! log-chan (format "[Regulator] Set charge rate to %sA" new-rate-amps))
                (-> result
                    (assoc :regulation (-> regulation
                                           (assoc :new-charge-rate-amps new-rate-amps)
                                           (assoc :used-solar-data true)))))))))

      (catch clojure.lang.ExceptionInfo e
        (case (:type (ex-data e))
          (throw e)))

      (catch Exception e
        (throw e)))))

(defn regulate-tesla-charge-loop
  [tesla->regulator solar->regulator log-chan error-chan]
  (try
    (loop [last-result nil
           last-regulation nil
           first-regulation nil
           solar-data nil]
      (async/>!! log-chan "[Regulator] Working...")
      (let [result (regulate-tesla-charge
                    tesla->regulator
                    solar->regulator
                    log-chan
                    last-regulation
                    last-regulation
                    solar-data)
            last-regulation (if (not (nil? (:regulation result)))
                              (:regulation result)
                              last-regulation)
            first-regulation (if (true? (:started-regulating result))
                               (:regulation result)
                               first-regulation)
            solar-data (if (not (nil? (:solar-data result)))
                         (:solar-data result)
                         solar-data)]

        (when-some [delay-until (:delay-until result)]
          (when (.isAfter delay-until (time-now))
            (async/>!! log-chan (format "[Regulator] Next run in %ss (%s)"
                                        (seconds-between-times (time-now) delay-until)
                                        delay-until))
            (Thread/sleep (millis-between-times (time-now) delay-until))))

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

(defn publish-data
  [tesla->logger solar->logger log-chan tesla-data solar-data]
  (let [result nil]
    (try
      (let [[value channel] (async/alts!! [tesla->logger
                                           solar->logger
                                           (async/timeout 100)])]
        (cond
          (= tesla->logger channel)
          (do
            (async/>!! log-chan (format "[Logger] Received Tesla data: {t=%s, %s keys} <- channel" (get-in value ["charge_state" "timestamp"]) (count value)))
            (-> result
                (assoc :tesla-data value)))

          (= solar->logger channel)
          (do
            (async/>!! log-chan (format "[Logger] Received solar data: {t=%s, %s keys} <- channel" (:timestamp (last value)) (count (last value))))
            (-> result
                (assoc :solar-data value)))

          :else
          (let [power-to-grid (get-in (last solar-data) [:power-to-grid-watts] 0)
                charge-rate-amps (get-in tesla-data ["charge_state" "charge_amps"] 0)
                battery-percent (get-in tesla-data ["charge_state" "battery_level"] 0)
                power-to-tesla (* charge-rate-amps tesla/power-to-current-3-phase)]
            (log-to-thingspeak
             "field1" (float power-to-grid)
             "field2" (float charge-rate-amps)
             "field3" (float battery-percent)
             "field4" (float power-to-tesla))
            (async/>!! log-chan (format "[Logger] Sent to ThingSpeak: field1=%s field2=%s field3=%s field4=%s"
                                        (float power-to-grid)
                                        (float charge-rate-amps)
                                        (float battery-percent)
                                        (float power-to-tesla)))
            (-> result
                (assoc :delay-until (time-after-seconds 30))))))

      (catch clojure.lang.ExceptionInfo e
        (case (:type (ex-data e))
          (throw e)))

      (catch Exception e
        (throw e)))))

(defn publish-data-loop
  [tesla->logger solar->logger log-chan error-chan]
  (try
    (loop [last-result nil
           last-tesla-data nil
           last-solar-data nil]
      (async/>!! log-chan "[Logger] Working...")
      (let [result (publish-data tesla->logger solar->logger log-chan last-tesla-data last-solar-data)
            tesla-data (if (not (nil? (:tesla-data result)))
                         (:tesla-data result)
                         last-tesla-data)
            solar-data (if (not (nil? (:solar-data result)))
                         (:solar-data result)
                         last-solar-data)]

        (when-some [delay-until (:delay-until result)]
          (when (.isAfter delay-until (time-now))
            (async/>!! log-chan (format "[Logger] Next run in %ss (%s)"
                                        (seconds-between-times (time-now) delay-until)
                                        delay-until))
            (Thread/sleep (millis-between-times (time-now) delay-until))))

        (recur result tesla-data solar-data)))
    (catch clojure.lang.ExceptionInfo e
      (async/>!! error-chan e))
    (catch Exception e
      (async/>!! error-chan e))))

(defn log-loop
  [log-chan error-chan]
  (try
    (loop []
      (let [message (async/<!! log-chan)]
        (when (nil? message)
          (throw (ex-info "Channel closed!" {})))
        (log :info message)
        (recur)))
    (catch clojure.lang.ExceptionInfo e
      (async/>!! error-chan e))
    (catch Exception e
      (async/>!! error-chan e))))

(defn -main
  [& args]
  (let [error-chan (async/chan (async/dropping-buffer 1))
        tesla->regulator (async/chan (async/sliding-buffer 1))
        tesla->logger (async/chan (async/sliding-buffer 1))
        solar->regulator (async/chan (async/sliding-buffer 1))
        solar->logger (async/chan (async/sliding-buffer 1))
        log-chan (async/chan 10)]

    (async/go
      (update-tesla-data-loop
       tesla->regulator
       tesla->logger
       log-chan
       error-chan))

    (async/go
      (update-solar-data-loop
       solar->regulator
       solar->logger
       log-chan
       error-chan))

    (async/go
      (regulate-tesla-charge-loop
       tesla->regulator
       solar->regulator
       log-chan
       error-chan))

    (async/go
      (publish-data-loop
       tesla->logger
       solar->logger
       log-chan
       error-chan))

    (async/go
      (log-loop
       log-chan
       error-chan))

    (let [e (async/<!! error-chan)]
      (when (not (nil? e))
        (printf "Critical error; %s%n" (ex-message e))
        (client/post "https://ntfy.sh/github-nqeng-tesla-solar-charger" {:body (ex-message e)})))

    (async/close! error-chan)
    (async/close! tesla->regulator)
    (async/close! tesla->logger)
    (async/close! solar->regulator)
    (async/close! solar->logger)))
