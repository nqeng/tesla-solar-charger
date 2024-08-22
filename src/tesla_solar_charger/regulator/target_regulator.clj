(ns tesla-solar-charger.regulator.target-regulator
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

(defn get-minutes-until-charge-percent-at-max-rate [car-state target-percent]
  (let [charge-power-watts (:charge-power-watts car-state)
        max-charge-power-watts (:max-charge-power-watts car-state)
        minutes-to-target-percent (get-minutes-until-charge-percent car-state target-percent)
        minutes-per-watt (/ minutes-to-target-percent (if (= 0.0 charge-power-watts) 1 max-charge-power-watts))
        minutes-to-target-percent-at-max-rate (* minutes-per-watt charge-power-watts)]
    minutes-to-target-percent-at-max-rate))

(defn should-override-to-reach-target?
  [car-state target-percent target-time]
  (let [minutes-to-target-percent-at-max-rate (get-minutes-until-charge-percent-at-max-rate car-state target-percent)
        minutes-left-to-charge (minutes-between-times (:timestamp car-state) target-time)]
    (< minutes-left-to-charge minutes-to-target-percent-at-max-rate)))

(defn make-target-time
  [target-hour target-minute]
  (let [local-time-now (java.time.ZonedDateTime/now)
        target-time (-> local-time-now
                        (.withHour target-hour)
                        (.withMinute target-minute))
        target-time (if (.isAfter target-time local-time-now) target-time (.plusDays target-time 1))
        target-time (.toInstant target-time)]
    target-time))

(defn regulate-new-data-point
  [car-name location options last-car-state last-data-point new-data-point charge-setter prefix]
  (let [{:keys [target-percent
                target-hour
                target-minute
                power-buffer-watts
                max-climb-watts
                max-drop-watts]} options
        target-time (make-target-time target-hour target-minute)
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

      (should-override-to-reach-target? last-car-state target-percent target-time)
      (infof "[%s] Automatic override active" prefix)

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
  (let [{:keys [target-percent
                target-hour
                target-minute
                power-buffer-watts
                max-climb-watts
                max-drop-watts]} options
        target-time (make-target-time target-hour target-minute)
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

        (should-override-to-reach-target? new-car-state target-percent target-time)
        (infof "[%s] Automatic override active" prefix)

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

      (and (not (should-override-to-reach-target? last-car-state target-percent target-time))
           (should-override-to-reach-target? new-car-state target-percent target-time))
      (do
        (infof "[%s] Overriding to reach %d%% by %s" prefix target-percent target-time)
        (set-charge-power charge-setter max-charge-power-watts))

      (and (should-override-to-reach-target? last-car-state target-percent target-time)
           (not (should-override-to-reach-target? new-car-state target-percent target-time)))
      (do
        (infof "[%s] Turning off automatic override" prefix)
        (set-charge-power charge-setter max-charge-power-watts))

      :else
      (debugf "[%s] No action" prefix))))

(def default-settings
  {:target-percent 80
   :target-hour 17
   :target-minute 0
   :power-buffer-watts 1000
   :max-climb-watts 5000
   :max-drop-watts 5000})

(defrecord TargetRegulator [car-name location last-car-state last-data-point prefix]
  IRegulator
  (regulate-new-car-state [regulator new-car-state charge-setter options]
    (let [settings (merge options default-settings)
          _ (regulate-new-car-state
             car-name
             location
             settings
             last-car-state
             new-car-state
             last-data-point
             charge-setter
             prefix)
          regulator (assoc regulator :last-car-state new-car-state)]
      regulator))
  (regulate-new-data-point [regulator new-data-point charge-setter options]
    (let [settings (merge options default-settings)
          _ (regulate-new-data-point
             car-name
             location
             settings
             last-car-state
             last-data-point
             new-data-point
             charge-setter
             prefix)
          regulator (assoc regulator :last-data-point new-data-point)]
      regulator)))

(defn new-TargetRegulator
  [car-name location prefix]
  (->TargetRegulator car-name location nil nil prefix))

