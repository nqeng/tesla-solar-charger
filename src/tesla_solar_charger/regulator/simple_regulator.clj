(ns tesla-solar-charger.regulator.simple-regulator
  (:require
    [taoensso.timbre :as timbre :refer [infof errorf debugf]]
    [tesla-solar-charger.car-charge-setter.car-charge-setter :refer [set-charge-power]]
    [tesla-solar-charger.haversine :refer [distance-between-geo-points-meters]]
    [tesla-solar-charger.regulator.regulator :refer [IRegulator]]))

(defn clamp-min-max
  [num min max]
  (cond
    (> num max) max
    (< num min) min
    :else       num))

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
  [charge-power-watts excess-power-watts power-buffer-watts max-climb-watts max-drop-watts]
  (let [available-power-watts (- excess-power-watts power-buffer-watts)
        adjustment-power-watts (clamp-min-max available-power-watts (- max-drop-watts) max-climb-watts)
        new-power-watts (+ charge-power-watts adjustment-power-watts)]
    new-power-watts))

(defn is-car-charging-at-location?
  [location car-state]
  (and (is-car-at-location? location car-state) (:is-charging car-state)))

(defn did-car-start-charging?
  [car-state last-car-state]
  (and (:is-charging car-state) (not (:is-charging last-car-state))))

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

(defn regulate-new-data-point
  [car-name location options last-car-state last-data-point new-data-point charge-setter prefix]
  (let [{:keys [power-buffer-watts 
                max-climb-watts 
                max-drop-watts]} options
        excess-power-watts (:excess-power-watts new-data-point)]
    (cond
      (nil? last-car-state)
      (infof "[%s] No car state" prefix)

      (not (is-car-at-location? location last-car-state))
      (infof "[%s] Car is not here" prefix)

      (not (:is-charging last-car-state))
      (infof "[%s] Car is not charging" prefix)

      (:is-override-active last-car-state)
      (infof "[%s] Override is active" prefix)

      (and (some? last-data-point)
           (= excess-power-watts (:excess-power-watts last-data-point)))
      (infof "[%s] No change to excess power" prefix)

      :else
      (let [new-charge-power-watts (calc-new-charge-power-watts
                                     (:charge-power-watts last-car-state)
                                     excess-power-watts
                                     power-buffer-watts
                                     max-climb-watts
                                     max-drop-watts)]
        (infof "[%s] Excess power is %.2fW; Car should charge at %.2fW" 
               prefix
               excess-power-watts 
               new-charge-power-watts)
        (set-charge-power charge-setter new-charge-power-watts)))))

(defn regulate-new-car-state
  [car-name location options last-car-state new-car-state last-data-point charge-setter prefix]
  (let [{:keys [power-buffer-watts
                max-climb-watts
                max-drop-watts]} options
        is-override-active (:is-override-active new-car-state)
        max-charge-power-watts (:max-charge-power-watts new-car-state)]
    (cond
      (and (nil? last-data-point) (nil? last-car-state))
      (infof "[%s] No previous car state" prefix)

      (nil? last-car-state)
      (cond
        (not (is-car-at-location? location new-car-state))
      (infof "[%s] Car is not here" prefix)

      (not (:is-charging new-car-state))
      (infof "[%s] Car is not charging" prefix)

      (:is-override-active new-car-state)
      (infof "[%s] Override is active" prefix)

      :else
      (let [new-charge-power-watts (calc-new-charge-power-watts
                                     (:charge-power-watts new-car-state)
                                     (:excess-power-watts last-data-point)
                                     power-buffer-watts
                                     max-climb-watts
                                     max-drop-watts)]
        (infof "[%s] Excess power is %.2fW; Car should charge at %.2fW" 
               prefix
               (:excess-power-watts last-data-point)
               new-charge-power-watts)
        (set-charge-power charge-setter new-charge-power-watts)))

      (and (did-car-stop-charging? new-car-state last-car-state)
           (did-car-leave-location? location new-car-state last-car-state))
      (infof "[%s] Car stopped charging and left" prefix)

      (did-car-leave-location? location new-car-state last-car-state)
      (infof "[%s] Car left" prefix)

      (not (is-car-at-location? location new-car-state))
      (infof "[%s] Car is not here" prefix)

      (and (did-car-enter-location? location new-car-state last-car-state)
           (did-car-start-charging? new-car-state last-car-state)
           is-override-active)
      (do
        (infof "[%s] Car entered and started charging with override" prefix)
        (set-charge-power charge-setter max-charge-power-watts))

      (and (did-car-enter-location? location new-car-state last-car-state)
           (did-car-start-charging? new-car-state last-car-state))
      (do
        (infof "[%s] Car entered and started charging" prefix)
        (set-charge-power charge-setter 0))

      (did-car-enter-location? location new-car-state last-car-state)
      (infof "[%s] Car entered" prefix)

      (and (did-car-stop-charging? new-car-state last-car-state))
      (infof "[%s] Car stopped charging" prefix)

      (not (:is-charging new-car-state))
      (infof "[%s] Car is not charging" prefix)

      (and (did-car-start-charging? new-car-state last-car-state)
           is-override-active)
      (do
        (infof "[%s] Car started charging with override" prefix)
        (set-charge-power charge-setter max-charge-power-watts))

      (and (did-car-start-charging? new-car-state last-car-state))
      (do
        (infof "[%s] Car started charging" prefix)
        (set-charge-power charge-setter 0))

      (did-override-turn-on? new-car-state last-car-state)
      (do
        (infof "[%s] Override turned on" prefix)
        (set-charge-power charge-setter max-charge-power-watts))

      (did-override-turn-off? new-car-state last-car-state)
      (do
        (infof "[%s] Override turned off" prefix)
        (set-charge-power charge-setter 0))

      :else
      (debugf "[%s] No action" prefix))))

(def default-settings
  {:power-buffer-watts 1000
   :max-climb-watts 1000
   :max-drop-watts 1000})

(defrecord SimpleRegulator [car-name location power-buffer-watts max-climb-watts max-drop-watts last-car-state last-data-point prefix]
  IRegulator
  (regulate-new-car-state [regulator new-car-state charge-setter]
    (let [_ (regulate-new-car-state
              car-name
              location
              {:power-buffer-watts power-buffer-watts
               :max-climb-watts max-climb-watts
               :max-drop-watts max-drop-watts}
              last-car-state
              new-car-state 
              last-data-point 
              charge-setter
              prefix)
          regulator (assoc regulator :last-car-state new-car-state)]
      regulator))
  (regulate-new-data-point [regulator new-data-point charge-setter]
    (let [_ (regulate-new-data-point
              car-name
              location
              {:power-buffer-watts power-buffer-watts
               :max-climb-watts max-climb-watts
               :max-drop-watts max-drop-watts}
              last-car-state
              last-data-point
              new-data-point
              charge-setter
              prefix)
          regulator (assoc regulator :last-data-point new-data-point)]
      regulator)))

(defn new-SimpleRegulator
  [car-name location power-buffer-watts max-climb-watts max-drop-watts prefix]
  (->SimpleRegulator car-name location power-buffer-watts max-climb-watts max-drop-watts nil nil prefix))

