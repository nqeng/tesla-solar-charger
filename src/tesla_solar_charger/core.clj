(ns tesla-solar-charger.core
  (:gen-class)
  (:require
   [tesla-solar-charger.tesla :as tesla]
   [tesla-solar-charger.car :as car]
   [clj-http.client :as client]
   [tesla-solar-charger.sungrow :as sungrow]
   [tesla-solar-charger.dummy-tesla :as dummy-tesla]
   [tesla-solar-charger.env :as env]
   [clojure.java.io :refer [make-parents]]
   [clojure.string :as str]
   [clojure.data.priority-map :refer [priority-map]]
   [cheshire.core :as json]
   [clojure.core.async :as async]
   [clojure.string :as s])
  (:import
   (java.time.format DateTimeFormatter)
   #_(tesla_solar_charger.dummy_tesla DummyTesla)
   (java.time LocalDateTime)
   (java.time.temporal ChronoUnit)))

(def charge-sites
  [{:latitude -19.291019003256217 :longitude 146.79517660781684 :user "reuben@nqeng.com.au" :password "absdq142" :ps-id "1152381_7_2_3" :ps-point "p8018"}
   {:latitude -19.276013838847156 :longitude 146.80377415971097 :user "reuben@nqeng.com.au" :password "absdq142" :ps-id "1152381_7_2_3" :ps-point "p8018"}])

(defn format-time
  [format-str time]
  (.format time (DateTimeFormatter/ofPattern format-str)))

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

    #_(when-not (or (s/starts-with? (first args) "[Logger]") (s/starts-with? (first args) "[Tesla]"))
        (println log-message))
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

(defn euclidean-distance
  [x1 y1 x2 y2]
  (Math/sqrt (+ (Math/pow (- x2 x1) 2) (Math/pow (- y2 y1) 2))))

(defn is-car-near-site?
  [car-state site]
  (< (euclidean-distance
      (car/get-latitude car-state)
      (car/get-longitude car-state)
      (:latitude site)
      (:longitude site)) 0.0005))

(defn update-car-state
  [tesla->state->regulator tesla->state->solar tesla->state->logger log-chan car]
  (let [result {:delay 10}]
    (try
      (let [car-state (car/get-state car)
            result (-> result
                       (assoc :car-state car-state)
                       (assoc :delay-until (time-after-seconds 10)))]

        (cond
          (false? (async/>!! tesla->state->regulator car-state))
          (throw (ex-info "Channel closed!" {}))

          (false? (async/>!! tesla->state->logger car-state))
          (throw (ex-info "Channel closed!" {}))

          (false? (async/>!! tesla->state->solar car-state))
          (throw (ex-info "Channel closed!" {}))

          :else
          (do
            (async/>!! log-chan (format "[Tesla] (car state) -> 3 channels"))
            result)))

      (catch clojure.lang.ExceptionInfo e
        (case (:type (ex-data e))

          (throw e)))
      (catch Exception e
        (throw e)))))

(defn update-car-state-loop
  [tesla->state->regulator tesla->state->solar tesla->state->logger log-chan error-chan car]
  (try
    (loop [last-result nil]
      (async/>!! log-chan "[Tesla] Working...")
      (let [result (update-car-state tesla->state->regulator tesla->state->solar tesla->state->logger log-chan car)]

        (when-some [delay-until (:delay-until result)]
          (when (.isAfter delay-until (time-now))
            (async/>!! log-chan (format "[Tesla] Next run in %ss (%s)"
                                        (seconds-between-times (time-now) delay-until)
                                        (format-time "yyyy-MM-dd HH:mm:ss" delay-until)))
            (Thread/sleep (millis-between-times (time-now) delay-until))))

        (recur result)))
    (catch clojure.lang.ExceptionInfo e
      (async/>!! error-chan e))
    (catch Exception e
      (async/>!! error-chan e))))

(defn update-data
  [site]
  (try
    (let [sungrow-token (if (:is-logged-in site) (:sungrow-token site) (sungrow/login))
          site (assoc site :sungrow-token sungrow-token)
          current-time (time-now)
          power-to-grid-watts (sungrow/get-power-to-grid sungrow-token current-time)
          solar-data (sungrow/get-data sungrow-token current-time current-time ["1152381_7_2_3" "p8018"])
          power-to-grid-1 (try (- (Float/parseFloat (last (first (get-in solar-data ["1152381_7_2_3" "p8018"]))))) (catch Exception e nil))
          data-point {:timestamp current-time :power-to-grid-watts power-to-grid-1}
          solar-data {:timestamp current-time :points [data-point]}
          next-data-publish-time (.plusMinutes (sungrow/get-next-data-publish-time current-time) 1)]
      (-> site
          (assoc :data solar-data)
          (assoc :next-data-available-time next-data-publish-time)))
    (catch clojure.lang.ExceptionInfo e
      (throw e))
    (catch Exception e
      (throw e))))

(defn get-power-to-grid
  [site default]
  (-> site
      :data
      :points
      last
      (get :power-to-grid-watts default)))

(defn update-site-data
  [solar->data->regulator car->state->solar solar->data->logger log-chan site]
  (let [result {:site site}
        [car-state channel] (async/alts!! [car->state->solar (async/timeout 100)])]

    (cond
      (nil? car-state)
      (throw (ex-info "Channel closed!" {}))

      (= channel car->state->solar)
      (-> result
          (assoc :site (first (filter #(is-car-near-site? car-state %) charge-sites))))

      (nil? site)
      (do
        (async/>!! log-chan "Car is not at a charge site")
        (assoc site :delay-until (time-after-seconds 10)))

      :else
      (try
        (let [site (update-data site)]
          (-> result
              (assoc :site (update-data site))))

        (catch clojure.lang.ExceptionInfo e
          (throw e))
        (catch Exception e
          (throw e))))))

(defn update-site-data-loop
  [solar->data->regulator tesla->state->solar solar->data->logger log-chan error-chan]
  (try
    (loop [last-result nil]
      (async/>!! log-chan "[Solar] Working...")
      (let [site (:site last-result)
            result (update-site-data
                    solar->data->regulator
                    tesla->state->solar
                    solar->data->logger
                    log-chan
                    site)]
        (when-some [delay-until (:delay-until result)]
          (when (.isAfter delay-until (time-now))
            (async/>!! log-chan (format "[Solar] Next run in %ss (%s)"
                                        (seconds-between-times (time-now) delay-until)
                                        (format-time "yyyy-MM-dd HH:mm:ss" delay-until)))
            (Thread/sleep (millis-between-times (time-now) delay-until))))
        (recur result)))
    (catch clojure.lang.ExceptionInfo e
      (async/>!! error-chan e))
    (catch Exception e
      (async/>!! error-chan e))))

(defn did-car-start-charging?
  [current-state previous-state]
  (and
   (not (nil? current-state))
   (not (nil? (:charge-site current-state)))
   (car/is-charging? current-state)
   (or
    (nil? previous-state)
    (nil? (:charge-site previous-state))
    (not= (:charge-site previous-state) (:charge-site current-state))
    (not (:is-charging previous-state))
    (not (nil? (:charge-site previous-state))))))

(defn did-car-stop-charging?
  [current-state previous-state]
  (and
   (some? current-state)
   (some? previous-state)
   (car/is-charging? previous-state)
   (some? (:charge-site previous-state))
   (or
    (not (car/is-charging? current-state))
    (nil? (:charge-site current-state)))))

(defn has-regulated-this-solar-data?
  [solar-data last-regulation]
  (and
   (not (nil? last-regulation))
   (true? (:used-solar-data last-regulation))
   (= (:timestamp (:solar-site last-regulation))
      (:timestamp solar-data))))

(defn time-from-epoch-millis
  [millis]
  (java.time.LocalDateTime/ofEpochSecond (long (/ millis 1000)) 0 java.time.ZoneOffset/UTC))

(defn regulate-tesla-charge
  [tesla->state->regulator solar->data->regulator log-chan last-regulation first-regulation solar-site]
  (let [result {:delay-until (time-after-seconds 10)}
        target-time (java.time.LocalDateTime/of (java.time.LocalDate/now) (java.time.LocalTime/of env/target-time-hour env/target-time-minute))]
    (try
      (let [[value channel] (async/alts!! [tesla->state->regulator solar->data->regulator])]

        (cond
          (nil? value)
          (throw (ex-info "Channel closed!" {}))

          (= channel solar->data->regulator)
          (do
            (async/>!! log-chan (format "[Regulator] (solar data) <- channel"))
            (-> result
                (assoc :solar-site value)
                (assoc :delay-until nil)))

          :else
          (let [car-state value
                regulation {:solar-site solar-site
                            :car-state car-state}]
            (async/>!! log-chan (format "[Regulator] (car state) <- channel"))
            (cond
              #_(did-car-start-charging? car-state (:car-state last-regulation))
              #_(let [starting-rate-amps 0]
                  (car/set-charge-rate car-state starting-rate-amps)
                  (car/set-charge-limit car-state env/target-percent)
                  (async/>!! log-chan (format "[Regulator] Tesla connected; set charge rate to %sA" starting-rate-amps))
                  (-> result
                      (assoc :regulation (-> regulation
                                             (assoc :new-charge-rate-amps starting-rate-amps)
                                             (assoc :new-charge-limit-percent 0)))
                      (assoc :started-regulating true)))

              #_(did-car-stop-charging? car-state (:car-state last-regulation))
              #_(let [initial-charge-rate-amps (car/get-charge-limit-percent (:car-state first-regulation))
                      initial-charge-limit-percent (car/get-charge-rate-amps (:car-state first-regulation))]
                  (car/set-charge-rate car-state initial-charge-rate-amps)
                  (car/set-charge-rate car-state initial-charge-limit-percent)
                  (async/>!! log-chan "[Regulator] Reset charge amps to before")
                  (async/>!! log-chan "[Regulator] Reset charge limit to before")
                  (-> result
                      (assoc :regulation (-> regulation
                                             (assoc :new-charge-rate-amps initial-charge-rate-amps)
                                             (assoc :new-charge-limit-percent initial-charge-limit-percent)))))

              (nil? solar-site)
              (do
                (async/>!! log-chan "[Regulator] Car is not at a solar site")
                result)

              (not (car/is-charging? car-state))
              (do
                (async/>!! log-chan "[Regulator] Car is not charging")
                result)

              (car/is-override-active? car-state)
              (let [max-rate-amps (car/get-max-charge-rate-amps car-state)]
                (car/set-charge-rate car-state max-rate-amps)
                (async/>!! log-chan (format "[Regulator] User override active; set charge rate to max (%sA)" max-rate-amps))
                (-> result
                    (assoc :regulation (-> regulation
                                           (assoc :new-charge-rate-amps 0)))))

              (not (car/will-reach-target-by? car-state target-time))
              (let [max-rate-amps (car/get-max-charge-rate-amps car-state)]
                (car/set-charge-rate car-state max-rate-amps)
                (async/>!! log-chan (format "[Regulator] Reaching %s%% by %s; set charge rate to max (%sA)"
                                            (car/get-charge-limit-percent car-state)
                                            (format-time "yyyy-MM-dd HH:mm:ss" target-time)
                                            max-rate-amps))
                (-> result
                    (assoc :regulation (-> regulation
                                           (assoc :new-charge-rate-amps max-rate-amps)))))

              (has-regulated-this-solar-data? solar-site last-regulation)
              (do
                (async/>!! log-chan "[Regulator] Already processed this solar data")
                result)

              (nil? (get-power-to-grid solar-site nil))
              (do
                (async/>!! log-chan "[Regulator] No data available from solar site")
                result)

              :else
              (let [excess-power (get-power-to-grid solar-site nil)
                    available-power-watts (- excess-power env/power-buffer-watts)
                    current-rate-amps (car/get-charge-rate-amps car-state)
                    max-charge-rate-amps (car/get-max-charge-rate-amps car-state)
                    adjustment-rate-amps (-> available-power-watts
                                             (/ tesla/power-to-current-3-phase)
                                             (limit (- env/max-drop-amps) env/max-climb-amps))
                    new-charge-rate-amps (-> current-rate-amps
                                             (+ adjustment-rate-amps)
                                             float
                                             Math/round
                                             int
                                             (limit 0 max-charge-rate-amps))]
                (car/set-charge-rate car-state new-charge-rate-amps)
                (async/>!! log-chan (format "[Regulator] Excess power is %sW (%sW available)" excess-power available-power-watts))
                (async/>!! log-chan (format "[Regulator] Set charge rate to %sA (%s%s from %sA)"
                                            new-charge-rate-amps
                                            (if (neg? adjustment-rate-amps) "-" "+")
                                            adjustment-rate-amps
                                            current-rate-amps))
                (-> result
                    (assoc :regulation (-> regulation
                                           (assoc :new-charge-rate-amps new-charge-rate-amps)
                                           (assoc :used-solar-data true)))))))))

      (catch clojure.lang.ExceptionInfo e
        (case (:type (ex-data e))
          (throw e)))

      (catch Exception e
        (throw e)))))

(defn regulate-tesla-charge-loop
  [tesla->state->regulator solar->data->regulator log-chan error-chan]
  (try
    (loop [last-result nil
           last-regulation nil
           first-regulation nil
           solar-data nil]
      (async/>!! log-chan "[Regulator] Working...")
      (let [result (regulate-tesla-charge
                    tesla->state->regulator
                    solar->data->regulator
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
            solar-data (if (not (nil? (:solar-site result)))
                         (:solar-site result)
                         solar-data)]

        (when-some [delay-until (:delay-until result)]
          (when (.isAfter delay-until (time-now))
            (async/>!! log-chan (format "[Regulator] Next run in %ss (%s)"
                                        (seconds-between-times (time-now) delay-until)
                                        (format-time "yyyy-MM-dd HH:mm:ss" delay-until)))
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
  [tesla->state->logger solar->data->logger log-chan car-state solar-site]
  (let [result nil]
    (try
      (let [[value channel] (async/alts!! [tesla->state->logger
                                           solar->data->logger
                                           (async/timeout 100)])]
        (cond
          (= tesla->state->logger channel)
          (do
            (async/>!! log-chan (format "[Logger] Received car state: (%s) <- channel"
                                        (format-time "yyyy-MM-dd HH:mm:ss" (:timestamp value))))
            (-> result
                (assoc :car-state value)))

          (= solar->data->logger channel)
          (do
            (async/>!! log-chan (format "[Logger] Received solar data: (%s) <- channel"
                                        (format-time "yyyy-MM-dd HH:mm:ss" (:timestamp value))))
            (-> result
                (assoc :solar-site value)))

          :else
          (let [power-to-grid (get-power-to-grid solar-site 0)
                charge-rate-amps (get-in car-state ["charge_state" "charge_amps"] 0)
                battery-percent (get-in car-state ["charge_state" "battery_level"] 0)
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
  [tesla->state->logger solar->data->logger log-chan error-chan]
  (try
    (loop [last-result nil
           last-tesla-data nil
           last-solar-data nil]
      (async/>!! log-chan "[Logger] Working...")
      (let [result (publish-data
                    tesla->state->logger
                    solar->data->logger
                    log-chan
                    last-tesla-data
                    last-solar-data)
            car (if (not (nil? (:car-state result)))
                  (:car-state result)
                  last-tesla-data)
            solar-site (if (not (nil? (:solar-site result)))
                         (:solar-site result)
                         last-solar-data)]
        (when-some [delay-until (:delay-until result)]
          (when (.isAfter delay-until (time-now))
            (async/>!! log-chan (format "[Logger] Next run in %ss (%s)"
                                        (seconds-between-times (time-now) delay-until)
                                        (format-time "yyyy-MM-dd HH:mm:ss" delay-until)))
            (Thread/sleep (millis-between-times (time-now) delay-until))))
        (recur result car solar-site)))
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

(defn create-regulation
  [car-state solar-site last-regulation]
  (let [regulation {:car-state car-state
                    :solar-site solar-site
                    :delay-next-regulation-until (time-after-seconds 10)
                    :initial-car-state (:initial-car-state last-regulation)}]
    (cond
      (did-car-start-charging? car-state (:car-state last-regulation))
      (-> regulation
          (assoc :charge-rate-amps 0)
          (assoc :charge-limit-percent env/target-percent)
          (assoc :initial-car-state car-state)
          (assoc :message "Car started charging"))

      (did-car-stop-charging? car-state (:car-state last-regulation))
      (-> regulation
          (assoc :charge-rate-amps (car/get-charge-rate-amps (:car-state last-regulation)))
          (assoc :charge-limit-percent (car/get-charge-limit-percent (:car-state last-regulation)))
          (assoc :message "Car stopped charging"))

      (nil? solar-site)
      (-> regulation
          (assoc :delay-next-regulation-until (time-after-seconds 60))
          (assoc :message "Car is not at a charge site"))

      (not (car/is-charging? car-state))
      (-> regulation
          (assoc :message "Car is not charging"))

      (car/is-override-active? car-state)
      (-> regulation
          (assoc :charge-rate-amps (car/get-max-charge-rate-amps car-state))
          (assoc :message "Override active"))

      (not (car/will-reach-target-by? car-state (get-target-time (time-now))))
      (-> regulation
          (assoc :charge-rate-amps (car/get-max-charge-rate-amps car-state))
          (assoc :message "Overriding to reach target"))

      (nil? (:data solar-site))
      (-> regulation
          (assoc :message "No solar data"))

      (and
       (= (:timestamp (:data solar-site)) (:timestamp (:data (:solar-site last-regulation))))
       (true? (:success last-regulation))
       (true? (:used-solar-data last-regulation)))
      (-> regulation
          (assoc :message "Already regulated this solar data"))

      (nil? (get-power-to-grid solar-site nil))
      (-> regulation
          (assoc :message "No excess power"))

      :else
      (let [excess-power (get-power-to-grid solar-site nil)
            available-power-watts (- excess-power env/power-buffer-watts)
            current-rate-amps (car/get-charge-rate-amps car-state)
            max-charge-rate-amps (car/get-max-charge-rate-amps car-state)
            adjustment-rate-amps (-> available-power-watts
                                     (/ tesla/power-to-current-3-phase)
                                     (limit (- env/max-drop-amps) env/max-climb-amps))
            new-charge-rate-amps (-> current-rate-amps
                                     (+ adjustment-rate-amps)
                                     float
                                     Math/round
                                     int
                                     (limit 0 max-charge-rate-amps))]
        (car/set-charge-rate car-state new-charge-rate-amps)
        (-> regulation
            (assoc :used-solar-data true)
            (assoc :charge-rate-amps new-charge-rate-amps)
            (assoc :message (format "[Regulator] Set charge rate to %sA (%s%s from %sA)"
                                    new-charge-rate-amps
                                    (if (neg? adjustment-rate-amps) "-" "+")
                                    adjustment-rate-amps
                                    current-rate-amps)))))))

(defn apply-regulation
  [log-chan car regulation]
  (when (some? (:message regulation))
    (async/>!! log-chan (:message regulation)))
  (when (some? (:charge-rate-amps regulation))
    (car/set-charge-rate car (:charge-rate-amps regulation)))
  (when (some? (:charge-limit-percent regulation))
    (car/set-charge-rate car (:charge-limit-percent regulation)))
  (async/>!! log-chan "[Main] Regulation applied"))

(defn run-program
  [log-chan tesla->state->logger solar->data->logger car solar-sites last-regulation]
  (try
    (async/>!! log-chan "[Main] Working...")
    (let [car-state (car/get-state car)
          solar-site (first (filter #(is-car-near-site? car-state %) solar-sites))
          solar-site (if (some? solar-site) (update-data solar-site) nil)]

      (when (and (some? car-state)
                 (false? (async/>!! tesla->state->logger car-state)))
        (throw (ex-info "Channel closed!" {})))

      (when (and (some? solar-site)
                 (false? (async/>!! solar->data->logger solar-site)))
        (throw (ex-info "Channel closed!" {})))

      (async/>!! log-chan "[Main] (car state) -> channel")
      (async/>!! log-chan "[Main] (solar data) -> channel")

      (let [regulation (create-regulation car-state solar-site last-regulation)
            delay-until (:delay-next-regulation-until regulation)]
        (try
          (apply-regulation log-chan car regulation)
          (catch clojure.lang.ExceptionInfo e
            (case (:type (ex-data e))
              (throw e)))
          (catch Exception e
            (throw e)))
        [(partial run-program
                  log-chan
                  tesla->state->logger
                  solar->data->logger
                  car
                  solar-sites
                  regulation)
         delay-until]))

    (catch clojure.lang.ExceptionInfo e
      (throw e))
    (catch Exception e
      (throw e))))

(defn main-loop
  [error-chan log-chan tesla->state->logger solar->data->logger car solar-sites]
  (try
    (loop [action (partial run-program log-chan tesla->state->logger solar->data->logger car solar-sites nil)]
      (let [[next-action delay-until] (action)]
        (when (and (some? delay-until)
                   (.isAfter delay-until (time-now)))
          (Thread/sleep (millis-between-times delay-until (time-now))))
        (recur next-action)))

    (catch clojure.lang.ExceptionInfo e
      (async/>!! error-chan e))
    (catch Exception e
      (async/>!! error-chan e))))

(defn -main
  [& args]
  (log :info "Starting...")
  (let [car (dummy-tesla/->DummyTesla nil nil nil)
        error-chan (async/chan (async/dropping-buffer 1))
        tesla->state->solar (async/chan (async/sliding-buffer 1))
        tesla->state->regulator (async/chan (async/sliding-buffer 1))
        tesla->state->logger (async/chan (async/sliding-buffer 1))
        solar->data->regulator (async/chan (async/sliding-buffer 1))
        solar->data->logger (async/chan (async/sliding-buffer 1))
        log-chan (async/chan 10)]

    (async/go
      (main-loop
       error-chan
       log-chan
       tesla->state->logger
       solar->data->logger
       car
       charge-sites))

    #_(async/go
        (publish-data-loop
         tesla->state->logger
         solar->data->logger
         log-chan
         error-chan))

    (async/go
      (log-loop
       log-chan
       error-chan))

    (let [e (async/<!! error-chan)]
      (when (not (nil? e))
        (let [stack-trace-string (with-out-str (clojure.stacktrace/print-stack-trace e))]
          (log :error stack-trace-string)
          (client/post "https://ntfy.sh/github-nqeng-tesla-solar-charger" {:body stack-trace-string}))))

    (async/close! error-chan)
    (async/close! log-chan)
    (async/close! tesla->state->regulator)
    (async/close! tesla->state->logger)
    (async/close! solar->data->regulator)
    (async/close! solar->data->logger)))

