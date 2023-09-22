(ns tesla-solar-charger.gophers.provide-car-state
  (:require
   [tesla-solar-charger.car :as car]
   [clojure.core.async :as async]
   [tesla-solar-charger.time-utils :refer [time-after-seconds
                                           time-now
                                           seconds-between-times
                                           format-time
                                           millis-between-times]]
   [better-cond.core :as b]
   [cheshire.core :as json]))

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

(defn which-charge-site?
  [car-state charge-sites]
  (->> charge-sites
       (filter #(is-car-near-site? car-state (last %)))
       first
       first))

(def solar-sites
  {"1" {:latitude -19.291019003256217
        :longitude 146.79517660781684
        :user "reuben@nqeng.com.au"
        :password "absdq142"
        :ps-id "1152381_7_2_3"
        :ps-point "p8018"}
   "2" {:latitude -19.276013838847156
        :longitude 146.80377415971097
        :user "reuben@nqeng.com.au"
        :password "absdq142"
        :ps-id "1152381_7_2_3"
        :ps-point "p8018"}})

(defn provide-car-state
  [tesla->state->regulator tesla->state->solar tesla->state->logger log-chan car]
  (let [result {:delay-until (time-after-seconds 10)}]
    (try
      (let [car-state (car/get-state car)
            solar-site-id (which-charge-site? car-state solar-sites)
            car-state (assoc car-state :solar-site-id solar-site-id)
            result (-> result
                       (assoc :car-state car-state))]
        (async/>!! log-chan {:level :verbose
                             :message (format "[Car] car state: %n%s"
                                              (json/generate-string car-state {:pretty true}))})
        (b/cond
          (or
           (false? (async/>!! tesla->state->regulator car-state))
           (false? (async/>!! tesla->state->logger car-state))
           (false? (async/>!! tesla->state->solar car-state)))
          (throw (ex-info "Channel closed!" {}))

          :do (async/>!! log-chan (format "[Car] (car state) -> 3 channels"))

          (nil? solar-site-id)
          (-> result
              (assoc :delay-until (time-after-seconds 60)))

          :do (async/>!! log-chan (format "[Car] car is at site %s" solar-site-id))

          (not (car/is-charging? car-state))
          (-> result
              (assoc :delay-until (time-after-seconds 30)))

          :do (async/>!! log-chan (format "[Car] car is charging"))

          :else
          result))

      (catch clojure.lang.ExceptionInfo e
        (case (:type (ex-data e))

          (throw e)))
      (catch Exception e
        (throw e)))))

(defn provide-car-state-loop
  [tesla->state->regulator tesla->state->solar tesla->state->logger log-chan error-chan car]
  (try
    (loop [last-result nil]
      (async/>!! log-chan "[Car] Working...")
      (let [result (provide-car-state
                    tesla->state->regulator
                    tesla->state->solar
                    tesla->state->logger
                    log-chan
                    car)]

        (when-some [delay-until (:delay-until result)]
          (when (.isAfter delay-until (time-now))
            (async/>!! log-chan (format "[Car] Next run in %ss (%s)"
                                        (seconds-between-times (time-now) delay-until)
                                        (format-time "yyyy-MM-dd HH:mm:ss" delay-until)))
            (Thread/sleep (millis-between-times (time-now) delay-until))))

        (recur result)))
    (catch clojure.lang.ExceptionInfo e
      (async/>!! error-chan e))
    (catch Exception e
      (async/>!! error-chan e))))
