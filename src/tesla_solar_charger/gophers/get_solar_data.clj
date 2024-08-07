(ns tesla-solar-charger.gophers.get-solar-data
  (:require
    [taoensso.timbre :as timbre :refer [infof debugf errorf]]
    [better-cond.core :refer [cond] :rename {cond better-cond}]
    [tesla-solar-charger.solar-data-source.solar-data-source :refer [get-latest-data-point]]
    [clojure.core.async :refer [>! alts! timeout chan go close!]]))

(defn is-data-point-newer?
  [data-point ?last-data-point]
  (or (nil? ?last-data-point)
      (.isAfter (:timestamp data-point) (:timestamp ?last-data-point))))

(defn make-data-point-message
  [data-point]
  (format
    "Excess power is %.2fW as of %s"
    (:excess-power-watts data-point) (:timestamp data-point)))

(defn fetch-new-solar-data
  [data-source output-ch kill-ch prefix]
  (go
    (infof "[%s] Process started" prefix)
    (loop [data-source data-source
           sleep-for 0
           ?last-data-point nil]

      (better-cond
        :let [timeout-ch (timeout (* 1000 sleep-for))]
        :let [[val ch] (alts! [kill-ch timeout-ch])]

        (= ch kill-ch) (infof "[%s] Received kill signal" prefix)

        :let [result-ch (go (get-latest-data-point data-source))]
        :let [[val ch] (alts! [kill-ch result-ch])]

        (= ch kill-ch) (infof "[%s] Received kill signal" prefix)

        :let [{err :err data-source :obj data-point :val} val]

        (some? err)
        (do
          (errorf "[%s] Failed to fetch solar data; %s" prefix err)
          (recur data-source 30 ?last-data-point))

        (not (is-data-point-newer? data-point ?last-data-point))
        (do
          (infof "[%s] No new solar data" prefix)
          (recur data-source 30 ?last-data-point))

        :do (infof "[%s] %s" prefix (make-data-point-message data-point)) 
        :do (debugf "[%s] Putting value on channel..." prefix)

        :let [success (>! output-ch data-point)]

        (false? success) (errorf "[%s] Output channel was closed" prefix)

        :do (debugf "[%s] Put value on channel" prefix)

        (recur data-source 30 data-point)))
    (infof "[%s] Process ended" prefix)))

