(ns tesla-solar-charger.car.car
  (:require))

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
   longitude
   readable-location-name]
  {:timestamp timestamp
   :is-connected is-connected
   :is-charging is-charging
   :is-override-active is-override-active
   :minutes-to-full-charge minutes-to-full-charge
   :charge-current-amps charge-current-amps
   :max-charge-current-amps max-charge-current-amps
   :charge-limit-percent charge-limit-percent
   :latitude latitude
   :longitude longitude
   :readable-location-name readable-location-name})

