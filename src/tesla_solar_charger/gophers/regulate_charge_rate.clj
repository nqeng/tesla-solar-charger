(ns tesla-solar-charger.gophers.regulate-charge-rate
  (:require
   [tesla-solar-charger.tesla :as tesla]
   [tesla-solar-charger.car :as car]
   [better-cond.core :as b]
   [tesla-solar-charger.env :as env]
   [tesla-solar-charger.time-utils :refer :all]
   [clojure.core.async :as async]))

(defn get-power-to-grid
  [site default]
  (let [power-to-grid (-> site
                          :data
                          :points
                          last
                          (get :power-to-grid-watts default))]
    (if (some? power-to-grid) power-to-grid default)))

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

(defn did-car-start-charging-here?
  [current-state previous-state]
  (and
   (some? current-state)
   (some? (:solar-site-id current-state))
   (car/is-charging? current-state)
   (or
    (nil? previous-state)
    (nil? (:solar-site-id previous-state))
    (not (car/is-charging? previous-state)))))

(defn did-car-stop-charging-here?
  [current-state previous-state]
  (and
   (some? current-state)
   (some? previous-state)
   (car/is-charging? previous-state)
   (some? (:solar-site-id previous-state))
   (or
    (not (car/is-charging? current-state))
    (nil? (:solar-site-id current-state)))))

(defn create-regulation
  [car-state solar-site last-attempted-regulation last-successful-regulation]
  (let [regulation {:car-state car-state
                    :solar-site solar-site
                    :initial-car-state (:initial-car-state last-attempted-regulation)
                    :messages ["Default"]}]
    (cond
      (did-car-start-charging-here? car-state (:car-state last-attempted-regulation))
      (-> regulation
          (assoc :charge-rate-amps 0)
          (assoc :charge-limit-percent env/target-percent)
          (assoc :initial-car-state car-state)
          (assoc :messages [(format "Car started charging; charge_rate=%sA, charge_limit=%s%%" 0 env/target-percent)]))

      (did-car-stop-charging-here? car-state (:car-state last-attempted-regulation))
      (let [charge-rate-amps (car/get-charge-rate-amps (:initial-car-state last-attempted-regulation))
            charge-limit-percent (car/get-charge-limit-percent (:initial-car-state last-attempted-regulation))]
        (-> regulation
            (assoc :charge-rate-amps charge-rate-amps)
            (assoc :charge-limit-percent charge-limit-percent)
            (assoc :messages [(format "Car stopped charging; charge rate=%sA charge limit=%s%%" charge-rate-amps charge-limit-percent)])))

      (nil? (:solar-site-id car-state))
      (-> regulation
          (assoc :messages ["Car is not at a solar site"]))

      (not (car/is-charging? car-state))
      (-> regulation
          (assoc :messages ["Car is not charging"]))

      (car/is-override-active? car-state)
      (-> regulation
          (assoc :charge-rate-amps (car/get-max-charge-rate-amps car-state))
          (assoc :messages ["Override active"]))

      #_(not (car/will-reach-target-by? car-state (get-target-time (time-now))))
      #_(-> regulation
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

(defn regulate-charge-rate
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

(defn regulate-charge-rate-loop
  [tesla->state->regulator solar->data->regulator log-chan error-chan car]
  (try
    (loop [last-result nil]
      (async/>!! log-chan "[Regulator] Working...")
      (let [result (regulate-charge-rate
                    tesla->state->regulator
                    solar->data->regulator
                    log-chan
                    (:last-attempted-regulation last-result)
                    (:last-successful-regulation last-result)
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
