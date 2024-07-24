(ns tesla-solar-charger.implementations.dummy-tesla
  (:require
   [tesla-solar-charger.interfaces.car :as car]
   [tesla-solar-charger.utils :as utils]
   [cheshire.core :as json]))

(def default-state
  (let [timestamp (utils/time-now)
        is-connected false
        is-charging false
        is-override-active false
        charge-limit-percent 80
        minutes-to-full-charge 0
        charge-current-amps 16
        max-charge-current-amps 16
        latitude 0
        longitude 0
        state (car/make-car-state
               timestamp
               is-connected
               is-charging
               is-override-active
               charge-limit-percent
               minutes-to-full-charge
               charge-current-amps
               max-charge-current-amps
               latitude
               longitude)]
    state))

(defrecord DummyTesla [vin]
  car/Car

  (get-state [car] default-state)
  (get-vin [car] vin)
  (get-name [car] "Dummy")
  (set-charge-rate [car new-charge-rate-amps])
  (restore-state [car state]))

