(ns tesla-solar-charger.core
  (:gen-class)
  (:require
   [tesla-solar-charger.tesla :as tesla]
   [tesla-solar-charger.car :as car]
   [better-cond.core :as b]
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
  (let [result {:delay-until (time-after-seconds 10)}]
    (try
      (let [car-state (car/get-state car)
            result (-> result
                       (assoc :car-state car-state))]

        (cond
          (or
           (false? (async/>!! tesla->state->regulator car-state))
           (false? (async/>!! tesla->state->logger car-state))
           (false? (async/>!! tesla->state->solar car-state)))
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

(defn get-id
  [site]
  (str (:latitude site) (:longitude site)))

(defn update-data
  [site]
  (try
    (let [sungrow-token (if (some? (:sungrow-token site)) (:sungrow-token site) (sungrow/login))
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
  (let [power-to-grid (-> site
                          :data
                          :points
                          last
                          (get :power-to-grid-watts default))]
    (if (some? power-to-grid) power-to-grid default)))

(defn which-charge-site?
  [car-state charge-sites]
  (first (filter #(is-car-near-site? car-state %) charge-sites)))

(defn update-site-data
  [solar->data->regulator car->state->solar solar->data->logger log-chan solar-site]
  (let [result {:site solar-site :delay-until (time-after-seconds 10)}]
    (try
      (let [[value channel] (async/alts!! [car->state->solar (async/timeout 100)])]

        (b/cond

          (and (= car->state->solar channel) (nil? value))
          (throw (ex-info "Channel closed!" {}))

          :let [new-car-state (if (= car->state->solar channel) value nil)]

          :do (when (some? new-car-state) (async/>!! log-chan "[Solar] (car state) <- channel"))

          :let [new-solar-site (if (some? new-car-state) (which-charge-site? new-car-state charge-sites) solar-site)]

          :do (when (not= (get-id new-solar-site) (get-id solar-site)) (async/>!! log-chan "[Solar] Car is at new site"))

          :let [solar-site (if (not= (get-id new-solar-site) (get-id solar-site)) new-solar-site solar-site)]

          (nil? solar-site)
          (do
            (async/>!! log-chan "[Solar] Car is not at a charge site")
            (-> result))

          :let [solar-site (update-data solar-site)]

          :let [result (-> result
                           (assoc :site solar-site))]

          (or
           (false? (async/>!! solar->data->regulator solar-site))
           (false? (async/>!! solar->data->logger solar-site)))
          (throw (ex-info "Channel closed" {}))

          :do (async/>!! log-chan "[Solar] (solar data) -> 2 channels")

          :else
          (-> result)))
      (catch clojure.lang.ExceptionInfo e
        (throw e))
      (catch Exception e
        (throw e)))))

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

(defn did-car-start-charging-here?
  [current-state previous-state solar-site]
  (and
   (some? current-state)
   (is-car-near-site? current-state solar-site)
   (car/is-charging? current-state)
   (or
    (nil? previous-state)
    (not (is-car-near-site? previous-state solar-site))
    (not (car/is-charging? previous-state)))))

(defn did-car-stop-charging-here?
  [current-state previous-state solar-site]
  (and
   (some? current-state)
   (some? previous-state)
   (car/is-charging? previous-state)
   (is-car-near-site? previous-state solar-site)
   (or
    (not (car/is-charging? current-state))
    (not (is-car-near-site? current-state solar-site)))))

(defn time-from-epoch-millis
  [millis]
  (java.time.LocalDateTime/ofEpochSecond (long (/ millis 1000)) 0 java.time.ZoneOffset/UTC))

(defn create-regulation
  [car-state solar-site last-attempted-regulation last-successful-regulation]
  (let [regulation {:car-state car-state
                    :solar-site solar-site
                    :initial-car-state (:initial-car-state last-attempted-regulation)
                    :messages ["Default"]}]
    (cond
      (did-car-start-charging-here? car-state (:car-state last-attempted-regulation) solar-site)
      (-> regulation
          (assoc :charge-rate-amps 0)
          (assoc :charge-limit-percent env/target-percent)
          (assoc :initial-car-state car-state)
          (assoc :messages [(format "Car started charging; charge_rate=%sA, charge_limit=%s%%" 0 env/target-percent)]))

      (did-car-stop-charging-here? car-state (:car-state last-attempted-regulation) solar-site)
      (-> regulation
          (assoc :charge-rate-amps (car/get-charge-rate-amps car-state))
          (assoc :charge-limit-percent (car/get-charge-limit-percent car-state))
          (assoc :messages ["Car stopped charging"]))

      (not (is-car-near-site? car-state solar-site))
      (-> regulation
          (assoc :messages ["Car is not at this charge site"]))

      (not (car/is-charging? car-state))
      (-> regulation
          (assoc :messages ["Car is not charging"]))

      (car/is-override-active? car-state)
      (-> regulation
          (assoc :charge-rate-amps (car/get-max-charge-rate-amps car-state))
          (assoc :messages ["Override active"]))

      (not (car/will-reach-target-by? car-state (get-target-time (time-now))))
      (-> regulation
          (assoc :charge-rate-amps (car/get-max-charge-rate-amps car-state))
          (assoc :messages ["Overriding to reach target"]))

      (nil? (:data solar-site))
      (-> regulation
          (assoc :messages ["No solar data"]))

      (and
       (= (:timestamp (:data solar-site)) (:timestamp (:data (:solar-site last-successful-regulation))))
       (true? (:used-solar-data last-successful-regulation)))
      (-> regulation
          (assoc :messages ["Already regulated this solar data"]))

      (nil? (get-power-to-grid solar-site nil))
      (-> regulation
          (assoc :messages ["No excess power"]))

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
        (-> regulation
            (assoc :used-solar-data true)
            (assoc :charge-rate-amps new-charge-rate-amps)
            (assoc :messages [(format "Excess power is %.2fW (%.2fW available)"
                                      (float excess-power)
                                      (float available-power-watts))
                              (format "Set charge rate to %sA (%s%s from %sA)"
                                      new-charge-rate-amps
                                      (if (neg? adjustment-rate-amps) "-" "+")
                                      (abs (int adjustment-rate-amps))
                                      current-rate-amps)]))))))

(defn apply-regulation
  [log-chan car regulation]
  (doseq [message (:messages regulation)]
    (async/>!! log-chan (str "[Regulator] " message)))
  (when (some? (:charge-rate-amps regulation))
    (car/set-charge-rate car (:charge-rate-amps regulation)))
  (when (some? (:charge-limit-percent regulation))
    (car/set-charge-limit car (:charge-limit-percent regulation)))
  (async/>!! log-chan "[Regulator] Regulation applied successfully"))

(defn regulate-tesla-charge
  [tesla->state->regulator solar->data->regulator log-chan last-attempted-regulation last-successful-regulation car]
  (let [result {}]
    (try
      (b/cond

        :let [car-state (async/<!! tesla->state->regulator)]

        :do (async/>!! log-chan (format "[Regulator] (car state) <- channel"))

        (nil? car-state)
        (throw (ex-info "Channel closed" {}))

        :let [[value channel] (async/alts!! [solar->data->regulator (async/timeout 100)])]

        (and
         (= solar->data->regulator channel)
         (nil? value))
        (throw (ex-info "Channel closed" {}))

        :let [new-solar-data value]

        :do (when (some? new-solar-data) (async/>!! log-chan "[Regulator] (solar data) <- channel"))

        :let [solar-data (if (some? new-solar-data) new-solar-data (:solar-data last-attempted-regulation))]

        (nil? solar-data)
        (do
          (async/>!! log-chan "[Regulator] No solar data received")
          result)

        :let [regulation (create-regulation car-state solar-data last-attempted-regulation last-successful-regulation)]

        :let [result (-> result
                         (assoc :last-attempted-regulation regulation))]

        :else
        (do
          (try
            (apply-regulation log-chan car regulation)
            (-> result
                (assoc :last-successful-regulation regulation))
            (catch clojure.lang.ExceptionInfo e
              ; network error, etc.
              (throw e))
            (catch Exception e
              (throw e)))))

      (catch clojure.lang.ExceptionInfo e
        (case (:type (ex-data e))
          (throw e)))

      (catch Exception e
        (throw e)))))

(defn regulate-tesla-charge-loop
  [tesla->state->regulator solar->data->regulator log-chan error-chan car]
  (try
    (loop [last-result nil]
      (async/>!! log-chan "[Regulator] Working...")
      (let [last-attempted-regulation (:last-attempted-regulation last-result)
            last-successful-regulation (:last-successful-regulation last-result)
            result (regulate-tesla-charge
                    tesla->state->regulator
                    solar->data->regulator
                    log-chan
                    last-attempted-regulation
                    last-successful-regulation
                    car)]

        (when-some [delay-until (:delay-until result)]
          (when (.isAfter delay-until (time-now))
            (async/>!! log-chan (format "[Regulator] Next run in %ss (%s)"
                                        (seconds-between-times (time-now) delay-until)
                                        (format-time "yyyy-MM-dd HH:mm:ss" delay-until)))
            (Thread/sleep (millis-between-times (time-now) delay-until))))

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

(defn publish-data
  [tesla->state->logger solar->data->logger log-chan previous-car-state previous-solar-site]
  (let [result nil]
    (try
      (let [[value channel] (async/alts!! [tesla->state->logger (async/timeout 100)])
            new-car-state (if (and (= channel tesla->state->logger) (nil? value)) (throw (ex-info "Channel closed" {})) value)
            car-state (if (some? new-car-state) new-car-state previous-car-state)
            [value channel] (async/alts!! [solar->data->logger (async/timeout 100)])
            new-solar-site (if (and (= channel solar->data->logger) (nil? value)) (throw (ex-info "Channel closed" {})) value)
            solar-data (if (some? new-solar-site) new-solar-site previous-solar-site)]
        (let [power-to-grid (get-power-to-grid solar-data 0)
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
              (assoc :car-state car-state)
              (assoc :solar-data solar-data)
              (assoc :delay-until (time-after-seconds 30)))))

      (catch clojure.lang.ExceptionInfo e
        (case (:type (ex-data e))
          (throw e)))

      (catch Exception e
        (throw e)))))

(defn publish-data-loop
  [tesla->state->logger solar->data->logger log-chan error-chan]
  (try
    (loop [last-result nil
           car-state nil
           solar-data nil]
      (async/>!! log-chan "[Logger] Working...")
      (let [result (publish-data
                    tesla->state->logger
                    solar->data->logger
                    log-chan
                    car-state
                    solar-data)]
        (when-some [delay-until (:delay-until result)]
          (when (.isAfter delay-until (time-now))
            (async/>!! log-chan (format "[Logger] Next run in %ss (%s)"
                                        (seconds-between-times (time-now) delay-until)
                                        (format-time "yyyy-MM-dd HH:mm:ss" delay-until)))
            (Thread/sleep (millis-between-times (time-now) delay-until))))
        (recur result (:car-state result) (:solar-data result))))
    (catch clojure.lang.ExceptionInfo e
      (async/>!! error-chan e))
    (catch Exception e
      (async/>!! error-chan e))))

(def log-blacklist
  ["[Solar]" "[Tesla]" "[Logger]"])

(defn is-blacklisted?
  [message]
  (not (not-any? (partial s/starts-with? message) log-blacklist)))

(defn log-loop
  [log-chan error-chan]
  (try
    (loop []
      (let [message (async/<!! log-chan)]
        (when (and (nil? message))
          (throw (ex-info "Channel closed!" {})))
        (when (not (is-blacklisted? message))
          (log :info message))
        (recur)))
    (catch clojure.lang.ExceptionInfo e
      (async/>!! error-chan e))
    (catch Exception e
      (async/>!! error-chan e))))

(defn -main
  [& args]
  (log :info "Starting...")
  (let [car (dummy-tesla/->DummyTesla "1234")
        error-chan (async/chan (async/dropping-buffer 1))
        tesla->state->solar (async/chan (async/sliding-buffer 1))
        tesla->state->regulator (async/chan (async/sliding-buffer 1))
        tesla->state->logger (async/chan (async/sliding-buffer 1))
        solar->data->regulator (async/chan (async/sliding-buffer 1))
        solar->data->logger (async/chan (async/sliding-buffer 1))
        log-chan (async/chan 10)]

    (async/go
      (update-car-state-loop
       tesla->state->regulator
       tesla->state->solar
       tesla->state->logger
       log-chan
       error-chan
       car))

    (async/go
      (update-site-data-loop
       solar->data->regulator
       tesla->state->solar
       solar->data->logger
       log-chan
       error-chan))

    (async/go
      (regulate-tesla-charge-loop
       tesla->state->regulator
       solar->data->regulator
       log-chan
       error-chan
       car))

    (async/go
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

