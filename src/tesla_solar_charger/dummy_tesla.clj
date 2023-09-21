(ns tesla-solar-charger.dummy-tesla
  (:require
   [tesla-solar-charger.car :as car]
   [cheshire.core :as json]))

(defrecord DummyTeslaState [object]

  car/CarState
  (is-charging? [state] (= "Charging" (get-in object ["charge_state" "charging_state"])))

  (is-override-active? [state] false)

  (will-reach-target-by? [car target-time] true)

  (get-charge-rate-amps [state] (get-in object ["charge_state" "charging_state"]))

  (get-charge-limit-percent [state] (get-in object ["charge_state" "charge_limit_soc"]))

  (get-max-charge-rate-amps [state] (get-in object ["charge_state" "charge_limit_soc"]))

  (get-latitude [state] (get-in object ["drive_state" "latitude"]))

  (get-longitude [state] (get-in object ["drive_state" "longitude"])))

(def default-state
  {"drive_state"
   {"longitude" 146.80377415971097, "latitude" -19.276013838847156},
   "vehicle_state" {"valet_mode" false},
   "charge_state"
   {"fast_charger_type" "<invalid>",
    "charger_power" 0,
    "conn_charge_cable" "<invalid>",
    "charge_rate" 0,
    "charger_voltage" 2,
    "charge_limit_soc_std" 80,
    "charge_port_door_open" false,
    "battery_level" 86,
    "timestamp" 1693458388783,
    "charge_current_request" 16,
    "charge_limit_soc_min" 50,
    "charger_actual_current" 0,
    "charge_miles_added_rated" 162,
    "time_to_full_charge" 0,
    "charging_state" "Charging",
    "minutes_to_full_charge" 0,
    "charge_limit_soc" 50,
    "charge_amps" 16,
    "charger_pilot_current" 16,
    "charge_current_request_max" 16,
    "charge_limit_soc_max" 100}})

(defrecord DummyTesla [vin current-state initial-state]
  car/Car

  (get-state [car]
    (let [new-state (try
                      (DummyTeslaState. (json/parse-string (slurp "test.json")))
                      (catch Exception e
                        (do
                          (try (spit "test.json" (json/generate-string default-state {:pretty true}))
                               (catch Exception e
                                 nil))

                          (DummyTeslaState. default-state))))]

      new-state))

  (get-vin [car] vin)

  (set-charge-rate [car new-charge-rate-amps]
    (let [new-state (-> current-state
                        (assoc-in ["charge_state" "charge_amps"] new-charge-rate-amps))]
      (spit "test.json" (json/generate-string new-state {:pretty true}))
      (assoc car :current-state new-state)))

  (restore-state [car state]
    (let [charge-rate-amps (car/get-charge-rate-amps state)
          charge-limit-percent (car/get-charge-limit-percent state)]
      (car/set-charge-rate car charge-rate-amps)
      (car/set-charge-limit car charge-limit-percent))))


