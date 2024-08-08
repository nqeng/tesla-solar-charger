(ns tesla-solar-charger.gophers.get-solar-data
  (:require
    [taoensso.timbre :as timbre :refer [infof debugf errorf]]
    [better-cond.core :refer [cond] :rename {cond better-cond}]
    [tesla-solar-charger.solar-data-source.solar-data-source :refer [get-latest-data-point]]
    [clojure.core.async :refer [>! <! alts! timeout chan go close!]]))

(defn is-data-point-newer?
  [data-point ?last-data-point]
  (or (nil? ?last-data-point)
      (.isAfter (:timestamp data-point) (:timestamp ?last-data-point))))

(defn make-data-point-message
  [data-point]
  (format
    "Excess power is %.2fW as of %s"
    (:excess-power-watts data-point) (:timestamp data-point)))

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

(defn fetch-new-solar-data
  [data-source output-ch kill-ch prefix]
  (close!
    (go
      (infof "[%s] Process started" prefix)
      (loop [data-source data-source
             next-poll-time (java.time.Instant/now)
             ?last-data-point nil]

        (better-cond
          :let [sleep-ch (go (sleep-until next-poll-time))]
          :let [[val ch] (alts! [kill-ch sleep-ch])]

          :do (close! sleep-ch)

          (= kill-ch ch) (infof "[%s] Received kill signal" prefix)

          :do (debugf "[%s] Fetching latest data point..." prefix)
          :let [result-ch (go (get-latest-data-point data-source))]
          :let [[val ch] (alts! [kill-ch result-ch])]

          :do (close! result-ch)

          (= kill-ch ch) (infof "[%s] Received kill signal" prefix)

          :let [{err :err data-source :obj data-point :val} val]

          (some? err)
          (let [next-poll-time (time-after-seconds 30)]
            (errorf "[%s] Failed to fetch solar data; %s" prefix err)
            (debugf "[%s] Sleeping until %s" prefix next-poll-time)
            (recur data-source next-poll-time ?last-data-point))

          (not (is-data-point-newer? data-point ?last-data-point))
          (let [next-poll-time (time-after-seconds 30)]
            (debugf "[%s] No new solar data" prefix)
            (debugf "[%s] Sleeping until %s" prefix next-poll-time)
            (recur data-source next-poll-time ?last-data-point))

          :do (infof "[%s] %s" prefix (make-data-point-message data-point)) 
          :do (debugf "[%s] Putting value on channel..." prefix)

          :let [[val ch] (alts! [[output-ch data-point] kill-ch])]

          (= kill-ch ch) (infof "[%s] Received kill signal" prefix)

          (false? val) (errorf "[%s] Output channel was closed" prefix)

          :let [next-poll-time (time-after-seconds 30)]
          :do (debugf "[%s] Put value on channel" prefix)
          :do (debugf "[%s] Sleeping until %s" prefix next-poll-time)
          (recur data-source next-poll-time data-point)))

      (infof "[%s] Process ended" prefix))))

