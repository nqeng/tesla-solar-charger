(ns tesla-solar-charger.car-data-source.car-data-source)

(defprotocol ICarDataSource
  (get-latest-car-state [data-source]))

(defn make-car-state
  [timestamp
   is-connected
   is-charging
   is-override-active
   charge-limit-percent
   minutes-to-full-charge
   charge-power-watts
   max-charge-power-watts
   battery-percent
   latitude
   longitude
   readable-location-name]
  {:timestamp timestamp
   :is-connected is-connected
   :is-charging is-charging
   :is-override-active is-override-active
   :minutes-to-full-charge minutes-to-full-charge
   :charge-power-watts charge-power-watts
   :max-charge-power-watts max-charge-power-watts
   :charge-limit-percent charge-limit-percent
   :battery-percent battery-percent
   :latitude latitude
   :longitude longitude
   :readable-location-name readable-location-name})

