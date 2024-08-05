(ns tesla-solar-charger.regulator.target-regulator
  (:require
   [tesla-solar-charger.haversine :refer [distance-between-geo-points-meters]]
   [tesla-solar-charger.regulator.regulator :refer [IRegulator make-regulation]]))

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

(defn get-minutes-until-charge-percent [car-state target-percent]
  (let [charge-limit-percent (:charge-limit-percent car-state)
        minutes-to-full-charge (:minutes-to-full-charge car-state)
        minutes-per-percent (/ minutes-to-full-charge charge-limit-percent)
        minutes-to-target-percent (* minutes-per-percent target-percent)]
    minutes-to-target-percent))

(defn minutes-between-times
  [start end]
  (.until start end java.time.temporal.ChronoUnit/MINUTES))

(defn will-reach-charge-percent-by-time? [car-state target-percent target-time]
  (< (get-minutes-until-charge-percent car-state target-percent)
     (minutes-between-times (:timestamp car-state) target-time)))

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
        minutes-left-to-charge (minutes-between-times (:timestamp car-state) target-time)]
    (< minutes-left-to-charge minutes-to-target-percent-at-max-rate)))

(defn make-regulation-from-new-car-state
  [location target-percent target-time last-car-state new-car-state]
  (let [is-override-active (:is-override-active new-car-state)
        max-charge-power-watts (:max-charge-power-watts new-car-state)]
    (cond
      (nil? last-car-state)
      (make-regulation nil "No previous car state")

      (and (did-car-stop-charging? new-car-state last-car-state)
           (did-car-leave-location? location new-car-state last-car-state))
      (make-regulation nil "Car stopped charging and left")

      (did-car-stop-charging? new-car-state last-car-state)
      (make-regulation nil "Car stopped charging")

      (did-car-leave-location? location new-car-state last-car-state)
      (make-regulation nil "Car left")

      (and (did-car-enter-location? location new-car-state last-car-state)
           (did-car-start-charging? new-car-state last-car-state)
           is-override-active)
      (make-regulation max-charge-power-watts "Car entered and started charging with override")

      (and (did-car-enter-location? location new-car-state last-car-state)
           (did-car-start-charging? new-car-state last-car-state))
      (make-regulation 0 "Car entered and started charging")

      (did-car-enter-location? location new-car-state last-car-state)
      (make-regulation nil "Car entered")

      (and (did-car-start-charging? new-car-state last-car-state)
           is-override-active)
      (make-regulation max-charge-power-watts "Car started charging with override")

      (did-car-start-charging? new-car-state last-car-state)
      (make-regulation 0 "Car started charging")

      (did-override-turn-on? new-car-state last-car-state)
      (make-regulation max-charge-power-watts "Override turned on")

      (did-override-turn-off? new-car-state last-car-state)
      (make-regulation 0 "Override turned off")

      (and (not (should-override-to-reach-target? last-car-state target-percent target-time))
           (should-override-to-reach-target? new-car-state target-percent target-time))
      (make-regulation max-charge-power-watts (format "Overriding to reach %d%% by %s" target-percent target-time))

      (and (should-override-to-reach-target? last-car-state target-percent target-time)
           (not (should-override-to-reach-target? new-car-state target-percent target-time)))
      (make-regulation max-charge-power-watts "Turning off automatic override")

      :else
      (make-regulation nil "No action"))))

(defn make-regulation-from-new-data-point
  [location target-percent target-time power-buffer-watts max-climp-watts max-drop-watts last-car-state last-data-point new-data-point]
  (let [excess-power-watts (:excess-power-watts new-data-point)]
    (cond
      (nil? last-car-state)
      (make-regulation nil "No car state")

      (not (is-car-at-location? location last-car-state))
      (make-regulation nil "Cat is not at this location")

      (not (:is-charging last-car-state))
      (make-regulation nil "Car is not charging")

      (:is-override-active last-car-state)
      (make-regulation nil "Override is active")

      (should-override-to-reach-target? last-car-state target-percent target-time)
      (make-regulation nil "Automatic override active")

      (and (some? last-data-point)
           (= excess-power-watts (:excess-power-watts last-data-point)))
      (make-regulation nil "No change to excess power")

      :else
      (let [new-charge-power-watts (calc-new-charge-power-watts
                                    (:charge-power-watts last-car-state)
                                    excess-power-watts
                                    power-buffer-watts
                                    max-climp-watts
                                    max-drop-watts)
            message (format "Excess power is %.2fW" excess-power-watts)]
        (make-regulation new-charge-power-watts message)))))

(defrecord TargetRegulator []
  IRegulator
  (make-regulation-from-new-car-state [regulator location new-car-state]
    (let [settings (:settings regulator)
          last-car-state (:last-car-state regulator)
          target-percent (get @settings :target-percent 80)
          target-time (get @settings :target-time (java.time.Instant/now))
          regulation (make-regulation-from-new-car-state
                      location
                      target-percent
                      target-time
                      last-car-state
                      new-car-state)
          regulator (assoc regulator :last-car-state new-car-state)]
      [regulator regulation]))
  (make-regulation-from-new-data-point [regulator location new-data-point]
    (let [settings (:settings regulator)
          last-car-state (:last-car-state regulator)
          last-data-point (:last-data-point regulator)
          target-percent (get @settings :target-percent 80)
          target-time (get @settings :target-time (java.time.Instant/now))
          power-buffer-watts (get @settings :power-buffer-watts 1000)
          max-climb-watts (get @settings :max-climb-watts 500)
          max-drop-watts (get @settings :max-drop-watts 500)
          regulation (make-regulation-from-new-data-point
                      location
                      target-percent
                      target-time
                      power-buffer-watts
                      max-climb-watts
                      max-drop-watts
                      last-car-state
                      last-data-point
                      new-data-point)
          regulator (assoc regulator :last-data-point new-data-point)]
      [regulator regulation])))

(defn new-TargetRegulator
  [settings]
  (let [the-map {:settings settings}
        defaults {}]
    (map->TargetRegulator (merge defaults the-map))))

