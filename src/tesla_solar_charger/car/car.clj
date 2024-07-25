(ns tesla-solar-charger.car.car
  (:require
    [tesla-solar-charger.utils :refer [minutes-between-times]])
  )

(defprotocol ICar
  (get-vin [car])
  (get-name [car])
  (get-state [car])
  (set-charge-current [car new-charge-rate-amps])
  (restore-this-state [car state-to-restore]))

(defn make-car-state
  [timestamp
   is-connected
   is-charging
   is-override-active
   charge-limit-percent
   minutes-to-full-charge
   charge-current-amps
   max-charge-current-amps
   latitude
   longitude]
  {:timestamp timestamp
   :is-connected is-connected
   :is-charging is-charging
   :is-override-active is-override-active
   :minutes-to-full-charge minutes-to-full-charge
   :charge-current-amps charge-current-amps
   :max-charge-current-amps max-charge-current-amps
   :charge-limit-percent charge-limit-percent
   :latitude latitude
   :longitude longitude})

(defn get-minutes-until-charge-percent [state target-percent]
  (let [charge-limit-percent (:charge-limit-percent state)
        minutes-to-full-charge (:minutes-to-full-charge state)
        minutes-per-percent (/ minutes-to-full-charge charge-limit-percent)
        minutes-to-target-percent (* minutes-per-percent target-percent)]
    minutes-to-target-percent))

(defn will-reach-charge-percent-by-time? [state target-percent target-time]
  (< (get-minutes-until-charge-percent state target-percent)
     (minutes-between-times (:timestamp state) target-time)))

(defn get-minutes-until-charge-percent-at-max-rate [state target-percent]
  (let [charge-rate-amps (:charge-current-amps state)
        max-charge-rate-amps (:max-charge-current-amps state)
        minutes-to-target-percent (get-minutes-until-charge-percent state target-percent)
        minutes-per-amp (/ minutes-to-target-percent max-charge-rate-amps)
        minutes-to-target-percent-at-max-rate (* minutes-per-amp charge-rate-amps)]
    minutes-to-target-percent-at-max-rate))

