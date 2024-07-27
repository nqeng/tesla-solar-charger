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

(defn make-regulation
  [new-power-watts message]
  {:new-charge-power-watts new-power-watts
   :message message})

(defn regulate-new-data-point
  [charger location data-point last-data-point last-car-state]
  (let [excess-power-watts (:excess-power-watts last-data-point)
        charge-power-watts (charger/get-car-charge-power-watts charger last-car-state)
        new-charge-power-watts (calc-new-charge-power-watts charge-power-watts excess-power-watts 0 16 16)]
    (cond
      (nil? last-car-state)
      (make-regulation nil "No car state")

      (not (is-car-charging-at-location? location last-car-state))
      (make-regulation nil "Car is not charging at this location")

      (:is-override-active last-car-state)
      (make-regulation nil "Override is active")

      (and (some? last-data-point)
           (= excess-power-watts (:excess-power-watts last-data-point)))
      (make-regulation nil "No change to excess power")

      (= new-charge-power-watts charge-power-watts)
      (make-regulation nil "No change to charge rate")

      :else
      (make-regulation new-charge-power-watts (format "Excess power is %.2fW" excess-power-watts)))))

(defn regulate-charge-rate
  [location car charger regulator car-state-ch solar-data-ch kill-ch]
  (let [log-prefix "regulate-charge-rate"
        output-ch (chan (sliding-buffer 1))]
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
                        regulation (regulator/regulate-new-car-state
                                    regulator
                                    car-state
                                    last-car-state
                                    last-data-point)]
                    (when (some? (:message regulation))
                      (log/info (:message regulation)))
                    (when (:should-set-charge-rate regulation)
                      (>! output-ch (:new-charge-power-watts regulation)))
                    (recur car-state last-data-point)))
                (do
                  (log/info log-prefix "Received new data point")
                  (let [data-point val
                        regulation (regulator/regulate-new-data-point
                                    regulator
                                    data-point
                                    last-data-point
                                    last-car-state)]
                    (when (some? (:message regulation))
                      (log/info (:message regulation)))
                    (when (:should-set-charge-rate regulation)
                      (>! output-ch (:new-charge-power-watts regulation)))
                    (recur last-car-state data-point))))))))
      (log/info log-prefix "Closing channel...")
      (close! output-ch)
      (log/info log-prefix "Process died"))
    output-ch))
