(ns tesla-solar-charger.gophers.provide-solar-data
  (:require
   [better-cond.core :as b]
   [tesla-solar-charger.sungrow :as sungrow]
   [tesla-solar-charger.time-utils :refer [time-after-seconds
                                           time-now
                                           seconds-between-times
                                           format-time
                                           millis-between-times]]
   [clojure.core.async :as async]))

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

(defn update-data
  [site]
  (try
    (let [site (if (some? (:sungrow-token site))
                 site
                 (let [new-token (sungrow/login)]
                   (-> site
                       (assoc :sungrow-token new-token)
                       (assoc :messages (conj (:messages site) "Logged in to Sungrow")))))

          current-time (time-now)
          power-to-grid-watts (sungrow/get-power-to-grid (:sungrow-token site) current-time)
          solar-data (sungrow/get-data (:sungrow-token site) current-time current-time ["1152381_7_2_3" "p8018"])
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

(comment
  "if no car state received or car state not at charge site:
       wait until car state received
       update state
   if channel closed:
       throw
   if new car state received:
       update state
   if car is not at charge site:
       return
   get site data
   send data over channels")

(defn provide-solar-data
  [solar->data->regulator car->state->solar solar->data->logger log-chan previous-car-state solar-sites]
  (let [result {:car-state previous-car-state}]
    (async/>!! log-chan {:level :verbose
                         :message (format "[Solar] solar sites: %n%s"
                                          #_(json/generate-string solar-sites {:pretty true})
                                          solar-sites)})
    (try
      (b/cond

        :let [car-state (if (or (nil? previous-car-state)
                                (nil? (:solar-site-id previous-car-state)))
                          (async/<!! car->state->solar)
                          previous-car-state)]

        (nil? car-state)
        (throw (ex-info "Channel closed!" {}))

        :let [result (-> result
                         (assoc :car-state car-state))]

        :do (when (nil? previous-car-state)
              (async/>!! log-chan "[Solar] (car state) <- channel"))

        :let [[value channel] (async/alts!! [car->state->solar (async/timeout 100)])]

        (and (= car->state->solar channel) (nil? value))
        (throw (ex-info "Channel closed!" {}))

        :let [new-car-state (if (= car->state->solar channel) value nil)]

        :do (when (some? new-car-state)
              (async/>!! log-chan "[Solar] (car state) <- channel"))

        :let [car-state (if (some? new-car-state) new-car-state car-state)
              result (-> result (assoc :car-state car-state))]

        (nil? (:solar-site-id car-state))
        (do
          (async/>!! log-chan "[Solar] Car is not at a charge site")
          (-> result))

        :do (when (not= (:solar-site-id car-state) (:solar-site-id previous-car-state))
              (async/>!! log-chan "[Solar] Car is at new site"))

        :let [solar-site (get solar-sites (:solar-site-id car-state))]

        :do (async/>!! log-chan {:level :verbose :message (format "[Solar] getting data from %s" solar-site)})

        :let [solar-site (update-data solar-site)]

        :do (when (seq? (:messages solar-site))
              (doseq [message (:messages solar-site)]
                (async/>!! log-chan {:level :info :message (str "[Solar] " message)})))

        :let [solar-site (dissoc solar-site :messages)]

        :let [result (-> result
                         (assoc-in [:solar-sites (:solar-site-id car-state)] solar-site))]

        (or
         (false? (async/>!! solar->data->regulator solar-site))
         (false? (async/>!! solar->data->logger solar-site)))
        (throw (ex-info "Channel closed" {}))

        :do (async/>!! log-chan "[Solar] (solar data) -> 2 channels")

        :else
        (-> result
            (assoc :delay-until (time-after-seconds 2))))
      (catch clojure.lang.ExceptionInfo e
        (throw e))
      (catch Exception e
        (throw e)))))

(defn provide-solar-data-loop
  [solar->data->regulator tesla->state->solar solar->data->logger log-chan error-chan]
  (try
    (loop [last-result {:solar-sites solar-sites}]
      (async/>!! log-chan "[Solar] Working...")
      (let [solar-sites (:solar-sites last-result)
            car-state (:car-state last-result)
            result (provide-solar-data
                    solar->data->regulator
                    tesla->state->solar
                    solar->data->logger
                    log-chan
                    car-state
                    solar-sites)]
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
