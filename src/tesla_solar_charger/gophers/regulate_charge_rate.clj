(ns tesla-solar-charger.gophers.regulate-charge-rate
  (:require
   [tesla-solar-charger.log :as log]
   [tesla-solar-charger.interfaces.regulator :as regulator]
   [tesla-solar-charger.haversine :refer [haversine]]
   [tesla-solar-charger.car.car :as car]
   [clojure.core.async :as async :refer [close! sliding-buffer chan alts! >! go]]
   [tesla-solar-charger.utils :as utils]
   [tesla-solar-charger.charger.charger :as charger]
   [cheshire.core :as json]))

(def distance-between-geo-points-kilometers haversine)

(defn distance-between-geo-points-meters
  [lat1 lng1 lat2 lng2]
  (* 1000 (distance-between-geo-points-kilometers {:lng lng1 :lat lat1} {:lng lng2 :lat lat2})))

(defn is-car-at-location?
  [location car-state]
  (let [range-meters 50
        distance-between-meters (distance-between-geo-points-meters
                                 (:latitude car-state)
                                 (:longitude car-state)
                                 (:latitude location)
                                 (:longitude location))]
    (< distance-between-meters range-meters)))

(defn calc-new-charge-power-watts
  [power-watts excess-power-watts power-buffer-watts max-climb-watts max-drop-watts]
  (let [available-power-watts (- excess-power-watts power-buffer-watts)
        adjustment-power-watts (utils/clamp-min-max available-power-watts (- max-drop-watts) max-climb-watts)
        new-power-watts (+ power-watts adjustment-power-watts)]
    new-power-watts))

(defn is-car-charging-at-location?
  [location car-state]
  (and (is-car-at-location? location car-state) (:is-charging car-state)))

(defn did-car-start-charging?
  [car-state last-car-state]
  (or (nil? last-car-state) (not (:is-charging last-car-state))))

(defn did-car-leave-location?
  [location car-state last-car-state]
  (and (not (is-car-at-location? location car-state)) (is-car-at-location? location last-car-state)))

(defn did-car-enter-location?
  [location car-state last-car-state]
  (and (is-car-at-location? location car-state) (not (is-car-at-location? location last-car-state))))

(defn did-car-stop-charging?
  [car-state last-car-state]
  (and (not (:is-charging car-state)) (:is-charging last-car-state)))

(defn did-override-turn-off?
  [car-state last-car-state]
  (and (not (:is-override-active car-state)) (:is-override-active last-car-state)))

(defn did-override-turn-on?
  [car-state last-car-state]
  (and (:is-override-active car-state) (not (:is-override-active last-car-state))))

(defn get-minutes-until-charge-percent [car-state target-percent]
  (let [charge-limit-percent (:charge-limit-percent car-state)
        minutes-to-full-charge (:minutes-to-full-charge car-state)
        minutes-per-percent (/ minutes-to-full-charge charge-limit-percent)
        minutes-to-target-percent (* minutes-per-percent target-percent)]
    minutes-to-target-percent))

(defn will-reach-charge-percent-by-time? [car-state target-percent target-time]
  (< (get-minutes-until-charge-percent car-state target-percent)
     (utils/minutes-between-times (:timestamp car-state) target-time)))

(defn get-minutes-until-charge-percent-at-max-rate [state target-percent]
  (let [charge-rate-amps (:charge-current-amps state)
        max-charge-rate-amps (:max-charge-current-amps state)
        minutes-to-target-percent (get-minutes-until-charge-percent state target-percent)
        minutes-per-amp (/ minutes-to-target-percent max-charge-rate-amps)
        minutes-to-target-percent-at-max-rate (* minutes-per-amp charge-rate-amps)]
    minutes-to-target-percent-at-max-rate))

(defn should-override-to-reach-target?
  [car-state target-percent target-time]
  (let [minutes-to-target-percent-at-max-rate (get-minutes-until-charge-percent-at-max-rate car-state target-percent)
        minutes-left-to-charge (utils/minutes-between-times (:timestamp car-state) target-time)]
    (< minutes-left-to-charge minutes-to-target-percent-at-max-rate)))

(defn regulate-charge-rate
  [location charger car-state-ch solar-data-ch charge-power-ch override-ch kill-ch target-percent target-time]
  (let [log-prefix "regulate-charge-rate"]
    (go
      (log/info log-prefix "Process starting...")
      (loop [last-car-state nil
             last-data-point nil]
        (let [[val ch] (alts! [kill-ch car-state-ch solar-data-ch])]
          (if (= ch kill-ch)
            (log/info log-prefix "Process dying...")
            (if (nil? val)
              (log/error log-prefix "Input channel was closed")
              (if (= ch car-state-ch)
                (do
                  (log/info log-prefix "Received new car state")
                  (let [car-state val
                        is-override-active (:is-override-active car-state)
                        max-charge-power-watts (charger/get-max-car-charge-power-watts charger car-state)]
                    (cond
                      (nil? last-car-state)
                      (do
                        (log/info log-prefix "No previous car state")
                        (recur car-state last-data-point))

                      (and (did-car-stop-charging? car-state last-car-state)
                           (did-car-leave-location? location car-state last-car-state))
                      (do
                        (log/info log-prefix "Car stopped charging and left")
                        (recur car-state last-data-point))

                      (did-car-stop-charging? car-state last-car-state)
                      (do
                        (log/info log-prefix "Car stopped charging")
                        (recur car-state last-data-point))

                      (did-car-leave-location? location car-state last-car-state)
                      (do
                        (log/info log-prefix "Car left")
                        (recur car-state last-data-point))

                      (and (did-car-enter-location? location car-state last-car-state)
                           (did-car-start-charging? car-state last-car-state)
                           is-override-active)
                      (do
                        (log/info log-prefix "Car entered and started charging with override")
                        (>! charge-power-ch max-charge-power-watts)
                        (recur car-state last-data-point))

                      (and (did-car-enter-location? location car-state last-car-state)
                           (did-car-start-charging? car-state last-car-state))
                      (do
                        (log/info log-prefix "Car entered and started charging")
                        (>! charge-power-ch 0)
                        (recur car-state last-data-point))

                      (did-car-enter-location? location car-state last-car-state)
                      (do
                        (log/info log-prefix "Car entered")
                        (recur car-state last-data-point))

                      (and (did-car-start-charging? car-state last-car-state)
                           is-override-active)
                      (do
                        (log/info log-prefix "Car started charging with override")
                        (>! charge-power-ch max-charge-power-watts)
                        (recur car-state last-data-point))

                      (did-car-start-charging? car-state last-car-state)
                      (do
                        (log/info log-prefix "Car started charging")
                        (>! charge-power-ch 0)
                        (recur car-state last-data-point))

                      (did-override-turn-on? car-state last-car-state)
                      (do
                        (log/info log-prefix "Override turned on")
                        (>! charge-power-ch max-charge-power-watts)
                        (recur car-state last-data-point))

                      (did-override-turn-off? car-state last-car-state)
                      (do
                        (log/info log-prefix "Override turned off")
                        (>! charge-power-ch 0)
                        (recur car-state last-data-point))

                      (and (not (:is-override-active car-state))
                           (not (should-override-to-reach-target? last-car-state target-percent target-time))
                           (should-override-to-reach-target? car-state target-percent target-time))
                      (do
                        (log/info log-prefix (format "Overriding to reach %d%% by %s" target-percent target-time))
                        (>! override-ch true)
                        (>! charge-power-ch max-charge-power-watts))

                      :else
                      (do
                        (log/info log-prefix "No action")
                        (recur car-state last-data-point)))))
                (do
                  (log/info log-prefix "Received new data point")
                  (let [data-point val
                        excess-power-watts (:excess-power-watts data-point)
                        last-excess-power-watts (:excess-power-watts last-data-point)
                        charge-power-watts (charger/get-car-charge-power-watts charger last-car-state)
                        new-charge-power-watts (calc-new-charge-power-watts charge-power-watts excess-power-watts 0 16 16)]
                    (cond
                      (nil? last-car-state)
                      (do
                        (log/info log-prefix "No car state")
                        (recur last-car-state data-point))

                      (not (is-car-charging-at-location? location last-car-state))
                      (do
                        (log/info "Car is not charging at this location")
                        (recur last-car-state data-point))

                      (:is-override-active last-car-state)
                      (do
                        (log/info log-prefix "Override is active")
                        (recur last-car-state data-point))

                      (and (some? last-data-point) (= excess-power-watts last-excess-power-watts))
                      (do
                        (log/info log-prefix "No change to excess power")
                        (recur last-car-state data-point))

                      (= new-charge-power-watts charge-power-watts)
                      (do
                        (log/info log-prefix "No change to charge rate")
                        (recur last-car-state data-point))

                      :else
                      (do
                        (log/info log-prefix (format "Excess power is %.2fW" excess-power-watts))
                        (>! charge-power-ch new-charge-power-watts)
                        (recur last-car-state data-point))))))))))

      (log/info log-prefix "Process died"))))
