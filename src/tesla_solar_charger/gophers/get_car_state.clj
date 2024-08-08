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
    "Car is at %s (%s)"
    (:readable-location-name car-state)
    (if (:is-charging car-state) 
      (format "charging at %.2fW" (float (:charge-power-watts car-state))) 
      "not charging")))

(defn fetch-new-car-state
  [data-source output-ch kill-ch prefix]
  (close!
    (go
      (infof "[%s] Process started" prefix)
      (loop [data-source data-source
             sleep-for 0
             ?last-car-state nil]

        (better-cond
          :let [timeout-ch (timeout (* 1000 sleep-for))]
          :let [[val ch] (alts! [kill-ch timeout-ch])]

          :do (close! timeout-ch)

          (= kill-ch ch) (infof "[%s] Received kill signal" prefix)

          :let [result-ch (go (get-latest-car-state data-source))]
          :let [[val ch] (alts! [kill-ch result-ch])]

          :do (close! result-ch)

          (= kill-ch ch) (infof "[%s] Received kill signal" prefix)

          :let [{err :err car-state :val data-source :obj} val]

          (some? err)
          (do
            (errorf "[%s] Failed to fetch car state; %s" prefix err)
            (recur data-source 30 ?last-car-state))

          (not (is-car-state-newer? car-state ?last-car-state))
          (do
            (infof "[%s] No new car state; sleeping for 30s" prefix)
            (recur data-source 30 ?last-car-state))

          :do (infof "[%s] %s" prefix (make-car-state-message car-state))
          :do (debugf "[%s] Putting value on channel..." prefix)

          :let [[val ch] (alts! [[output-ch car-state] kill-ch])]

          (= kill-ch ch) (infof "[%s] Received kill signal" prefix)

          (false? val) (errorf "[%s] Output channel was closed" prefix)

          :do (debugf "[%s] Put value on channel" prefix)

          (recur data-source 30 car-state)))

      (infof "[%s] Process ended" prefix))))

