(ns tesla-solar-charger.gophers.publish-data
  (:require
   [tesla-solar-charger.interfaces.site :as site]
   [tesla-solar-charger.utils :as utils]
   [clj-http.client :as client]
   [tesla-solar-charger.interfaces.car :as car]
   [clojure.core.async :as async]))

#_(defn publish-data
  [tesla->state->logger solar->data->logger log-chan previous-car-state previous-solar-site]
  (let [result nil]
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
  [log-prefix thingspeak-api-key car-state-chan site-data-chan error-chan log-chan]
  (async/go
    (try
      (loop []
        (try
          (async/>! log-chan {:level :verbose
                              :prefix log-prefix
                              :message "..."})
          (let [car-state (async/<! car-state-chan)
                site-data (async/<! site-data-chan)]
            (when (nil? car-state)
              (throw (ex-info "Channel closed" {})))
            (when (nil? site-data)
              (throw (ex-info "Channel closed" {})))
            (let [excess-power (-> site-data
                                   site/get-points
                                   last
                                   site/get-excess-power-watts)
                  excess-power (float (if (nil? excess-power) 0 excess-power))
                  charge-rate-amps (float (car/get-charge-rate-amps car-state))
                  battery-percent (float (car/get-battery-level-percent car-state))
                  tesla-power-draw-watts (float (* 1000 (car/get-charger-power-kilowatts car-state)))]
              (log-to-thingspeak
               thingspeak-api-key
               "field1" excess-power
               "field2" charge-rate-amps
               "field3" battery-percent
               "field4" tesla-power-draw-watts)
              (async/>! log-chan {:level :info
                                  :prefix log-prefix
                                  :message "Published data to Thingspeak"})
              (async/>! log-chan {:level :verbose
                                  :prefix log-prefix
                                  :message (format "field1=%s, field2=%s, field3=%s, field4=%s"
                                                   excess-power
                                                   charge-rate-amps
                                                   battery-percent
                                                   tesla-power-draw-watts)})
              (when-let [next-state-available-time (utils/time-after-seconds 30)]
                (when (.isAfter next-state-available-time (java.time.LocalDateTime/now))
                  (async/>! log-chan {:level :info
                                      :prefix log-prefix
                                      :message (format "Sleeping until %s"
                                                       (utils/format-local-time "yyyy-MM-dd HH:mm:ss" next-state-available-time))})

                  (Thread/sleep (utils/millis-between-times (java.time.LocalDateTime/now) next-state-available-time))))))
          (catch clojure.lang.ExceptionInfo e
            (case (:type (ex-data e))
              :network-error
              (do
                (async/>! log-chan {:level :error
                                    :prefix log-prefix
                                    :message (ex-message e)})
                (Thread/sleep 10000))

              (async/>! error-chan e))))
        (recur))

      (catch clojure.lang.ExceptionInfo e
        (async/>! error-chan e))

      (catch Exception e
        (async/>! error-chan e)))))

#_(defn publish-data-loop
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
