(ns tesla-solar-charger.gophers.get-car-state
  (:require
    [taoensso.timbre :as timbre :refer [infof errorf debugf]]
    [better-cond.core :refer [cond] :rename {cond better-cond}]
    [tesla-solar-charger.car-data-source.car-data-source :refer [get-latest-car-state]]
    [clojure.core.async :refer [>! close! timeout alts! chan <! go]]))

(defn is-car-state-newer?
  [car-state ?last-car-state]
  (or (nil? ?last-car-state)
      (.isAfter (:timestamp car-state) (:timestamp ?last-car-state))))

(defn make-car-state-message
  [car-state]
  (format
    "Car is %s"
    (if (:is-charging car-state) 
      (format "charging at %.2fW (%.2f%%)" (float (:charge-power-watts car-state)) (float (:battery-percent car-state)))
      "not charging")))

(defn time-after-seconds
  [seconds]
  (.plusSeconds (java.time.Instant/now) seconds))

(defn sleep-until
  [datetime]
  (let [millis (.until (java.time.Instant/now) 
                       datetime 
                       java.time.temporal.ChronoUnit/MILLIS)]
    (when (pos? millis)
      (Thread/sleep millis))))

(defn fetch-new-car-state
  [data-source output-ch kill-ch prefix]
  (close!
    (go
      (infof "[%s] Process started" prefix)
      (loop [data-source data-source
             next-poll-time (java.time.Instant/now)
             ?last-car-state nil]

        (better-cond
          :let [sleep-ch (go (sleep-until next-poll-time))]
          :let [[val ch] (alts! [kill-ch sleep-ch])]

          :do (close! sleep-ch)

          (= kill-ch ch) (infof "[%s] Received kill signal" prefix)

          :do (debugf "[%s] Fetching latest car state..." prefix)
          :let [result-ch (go (get-latest-car-state data-source))]
          :let [[val ch] (alts! [kill-ch result-ch])]

          :do (close! result-ch)

          (= kill-ch ch) (infof "[%s] Received kill signal" prefix)

          :let [{err :err car-state :val data-source :obj} val]

          (some? err)
          (let [next-poll-time (time-after-seconds 30)]
            (errorf "[%s] Failed to fetch car state; %s" prefix err)
            (debugf "[%s] Sleeping until %s" prefix next-poll-time)
            (recur data-source next-poll-time ?last-car-state))

          (not (is-car-state-newer? car-state ?last-car-state))
          (let [next-poll-time (time-after-seconds 30)]
            (debugf "[%s] No new car state" prefix)
            (debugf "[%s] Sleeping until %s" prefix next-poll-time)
            (recur data-source next-poll-time ?last-car-state))

          :do (infof "[%s] %s" prefix (make-car-state-message car-state))
          :do (debugf "[%s] Putting value on channel..." prefix)

          :let [[val ch] (alts! [[output-ch car-state] kill-ch])]

          (= kill-ch ch) (infof "[%s] Received kill signal" prefix)

          (false? val) (errorf "[%s] Output channel was closed" prefix)

          :do (debugf "[%s] Put value on channel" prefix)

          :let [next-poll-time (time-after-seconds 30)]
          :do (debugf "[%s] Sleeping until %s" prefix next-poll-time)
          (recur data-source next-poll-time car-state)))

      (infof "[%s] Process ended" prefix))))

