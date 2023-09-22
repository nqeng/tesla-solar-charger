(ns tesla-solar-charger.core
  (:gen-class)
  (:require
   [tesla-solar-charger.tesla :as tesla]
   [tesla-solar-charger.car :as car]
   [better-cond.core :as b]
   [clj-http.client :as client]
   [tesla-solar-charger.gophers.provide-car-state :refer [provide-car-state-loop]]
   [tesla-solar-charger.gophers.regulate-charge-rate :refer [regulate-charge-rate-loop]]
   [tesla-solar-charger.gophers.provide-solar-data :refer [provide-solar-data-loop]]
   [tesla-solar-charger.gophers.logger :refer [log-loop]]
   [tesla-solar-charger.dummy-tesla :as dummy-tesla]
   [tesla-solar-charger.env :as env]
   [tesla-solar-charger.time-utils :refer :all]
   [clojure.core.async :as async]))



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

(defn get-power-to-grid
  [site default]
  (let [power-to-grid (-> site
                          :data
                          :points
                          last
                          (get :power-to-grid-watts default))]
    (if (some? power-to-grid) power-to-grid default)))

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

(defn -main
  [& args]
  (println "Starting...")
  (let [car (dummy-tesla/->DummyTesla "1234")
        error-chan (async/chan (async/dropping-buffer 1))
        tesla->state->solar (async/chan (async/sliding-buffer 1))
        tesla->state->regulator (async/chan (async/sliding-buffer 1))
        tesla->state->logger (async/chan (async/sliding-buffer 1))
        solar->data->regulator (async/chan (async/sliding-buffer 1))
        solar->data->logger (async/chan (async/sliding-buffer 1))
        log-chan (async/chan 10)]

    (async/go
        (provide-car-state-loop
         tesla->state->regulator
         tesla->state->solar
         tesla->state->logger
         log-chan
         error-chan
         car))

    (async/go
        (provide-solar-data-loop
         solar->data->regulator
         tesla->state->solar
         solar->data->logger
         log-chan
         error-chan))

    (async/go
      (regulate-charge-rate-loop
       tesla->state->regulator
       solar->data->regulator
       log-chan
       error-chan
       car))

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
          (async/>!! log-chan {:level :error :message stack-trace-string})
          (client/post "https://ntfy.sh/github-nqeng-tesla-solar-charger" {:body stack-trace-string}))))

    (async/close! error-chan)
    (async/close! log-chan)
    (async/close! tesla->state->regulator)
    (async/close! tesla->state->logger)
    (async/close! solar->data->regulator)
    (async/close! solar->data->logger)))

