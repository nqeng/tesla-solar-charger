(ns tesla-solar-charger.gophers.publish-data
  (:require
   [clojure.core.async :as async]))

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
