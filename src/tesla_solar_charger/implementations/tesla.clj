(ns tesla-solar-charger.implementations.tesla
  (:require
   [tesla-solar-charger.time-utils :as time-utils]
   [tesla-solar-charger.interfaces.car :as car]
   [tesla-solar-charger.tesla :as tesla]))

(defrecord TeslaState [object]

  car/CarState
  (get-time [state] (time-utils/time-from-epoch-millis (get-in object ["charge_state" "timestamp"])))

  (get-id [state]
    (str
     (get-in object ["charge_state" "timestamp"])
     (get-in object ["drive_state" "timestamp"])))

  (is-charging? [state] (= "Charging" (get-in object ["charge_state" "charging_state"])))

  (is-override-active? [state] (true? (get-in object ["vehicle_state" "valet_mode"])))

  (will-reach-target-by? [car target-time] true)

  (get-charge-rate-amps [state] (get-in object ["charge_state" "charge_amps"]))

  (get-charge-limit-percent [state] (get-in object ["charge_state" "charge_limit_soc"]))

  (get-max-charge-rate-amps [state] (get-in object ["charge_state" "charge_current_request_max"]))

  (get-latitude [state] (get-in object ["drive_state" "latitude"]))

  (get-longitude [state] (get-in object ["drive_state" "longitude"])))

(defrecord Tesla [vin auth-token]
  car/Car

  (get-state [car]
    (->TeslaState (tesla/get-data vin auth-token)))

  (get-vin [car] vin)

  (get-name [car] "Tesla")

  (set-charge-rate [car new-charge-rate-amps]
    (tesla/set-charge-rate vin auth-token new-charge-rate-amps))

  (set-charge-limit [car new-charge-limit-percent]
    (tesla/set-charge-limit vin auth-token new-charge-limit-percent))

  (restore-state [car state]
    (let [charge-rate-amps (car/get-charge-rate-amps state)
          charge-limit-percent (car/get-charge-limit-percent state)]
      (car/set-charge-rate car charge-rate-amps)
      (car/set-charge-limit car charge-limit-percent))))
