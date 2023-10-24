(ns tesla-solar-charger.implementations.dummy-tesla
  (:require
   [tesla-solar-charger.interfaces.car :as car]
   [tesla-solar-charger.utils :as utils]
   [cheshire.core :as json]))

(defrecord DummyTeslaState [object]

  car/CarState
  (get-time [state] (utils/time-from-epoch-millis (get-in object ["charge_state" "timestamp"])))

  (get-id [state] (str (utils/time-now)))

  (is-charging? [state] (= "Charging" (get-in object ["charge_state" "charging_state"])))

  (is-override-active? [state] (true? (get-in object ["vehicle_state" "valet_mode"])))

  (get-charge-rate-amps [state] (get-in object ["charge_state" "charge_amps"]))

  (get-charge-limit-percent [state] (get-in object ["charge_state" "charge_limit_soc"]))

  (get-battery-level-percent [state] (get-in object ["charge_state" "battery_level"]))

  (get-charger-power-kilowatts [state] (get-in object ["charge_state" "charger_power"]))

  (get-minutes-to-full-charge [state] (get-in object ["charge_state" "minutes_to_full_charge"]))

  (get-minutes-to-target-percent [state target-percent]
    (let [charge-limit-percent (car/get-charge-limit-percent state)
          minutes-to-full-charge (car/get-minutes-to-full-charge state)
          minutes-per-percent (/ minutes-to-full-charge charge-limit-percent)
          minutes-to-target-percent (* minutes-per-percent target-percent)]
      minutes-to-target-percent))

  (get-minutes-to-target-percent-at-max-rate [state target-percent]
    (let [charge-rate-amps (car/get-charge-rate-amps state)
          max-charge-rate-amps (car/get-max-charge-rate-amps state)
          minutes-to-target-percent (car/get-minutes-to-target-percent state target-percent)
          minutes-per-amp (/ minutes-to-target-percent max-charge-rate-amps)
          minutes-to-target-percent-at-max-rate (* minutes-per-amp charge-rate-amps)]
      minutes-to-target-percent-at-max-rate))

  (get-max-charge-rate-amps [state] (get-in object ["charge_state" "charge_current_request_max"]))

  (will-reach-target-by? [state target-percent target-time]
    (< (car/get-minutes-to-target-percent state target-percent)
       (utils/calc-minutes-between-times (car/get-time state) target-time)))

  (should-override-to-reach-target?
    [state target-percent target-time]
    (let [minutes-to-target-percent-at-max-rate (car/get-minutes-to-target-percent-at-max-rate state target-percent)
          minutes-left-to-charge (utils/calc-minutes-between-times (car/get-time state) target-time)]
      (< minutes-left-to-charge minutes-to-target-percent-at-max-rate)))

  (get-latitude [state] (get-in object ["drive_state" "latitude"]))

  (get-longitude [state] (get-in object ["drive_state" "longitude"])))

(def default-state
  {"drive_state"
   {"longitude" 146.80377415971097,
    "latitude" -19.276013838847156},
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

(defrecord DummyTesla [vin]
  car/Car

  (get-state [car]
    (let [new-state (try
                      (-> car
                          car/get-vin
                          (str ".json")
                          slurp
                          json/parse-string
                          ->DummyTeslaState
                          #_(assoc-in [:object "charge_state" "timestamp"] (* 1000 (.getEpochSecond (java.time.Instant/now)))))
                      (catch Exception e
                        (do
                          (try (spit (str (car/get-vin car) ".json") (json/generate-string default-state {:pretty true}))
                               (catch Exception e
                                 nil))

                          (DummyTeslaState. default-state))))]

      new-state))

  (get-vin [car] vin)

  (get-name [car] "Dummy")

  (set-charge-rate [car new-charge-rate-amps]
    (let [new-state
          (try
            (-> (DummyTeslaState. (json/parse-string (slurp (str (car/get-vin car) ".json")))))
            (catch Exception e
              default-state))
          new-state (assoc-in new-state [:object "charge_state" "charge_amps"] new-charge-rate-amps)]

      (spit (str (car/get-vin car) ".json") (json/generate-string (:object new-state) {:pretty true}))))

  (restore-state [car state]
    (let [charge-rate-amps (car/get-charge-rate-amps state)
          charge-limit-percent (car/get-charge-limit-percent state)]
      (car/set-charge-rate car charge-rate-amps))))

(comment
  (let [car (->DummyTesla "1234")
        car-state (-> (car/get-state car)
                      (assoc-in [:object "charge_state" "charge_limit_soc"] 80)
                      (assoc-in [:object "charge_state" "charge_amps"] 8)
                      (assoc-in [:object "charge_state" "minutes_to_full_charge"] 120))
        target-time (-> (java.time.LocalDateTime/now)
                        (.withHour 16)
                        (.withMinute 30)
                        (.withSecond 0)
                        (.withNano 0))
        target-percent 60]
    (assert (= 80 (car/get-charge-limit-percent car-state)))
    (assert (= 8 (car/get-charge-rate-amps car-state)))
    (assert (= 120 (car/get-minutes-to-full-charge car-state)))
    (printf "Reaching %s%% by %s at %sA%n"
            (car/get-charge-limit-percent car-state)
            (utils/format-time "HH:mm:ss" (.plusMinutes (car/get-time car-state) (car/get-minutes-to-full-charge car-state)))
            (car/get-charge-rate-amps car-state))
    (printf "Will reach %s%% by %s at %sA%n"
            target-percent
            (utils/format-time "HH:mm:ss" (.plusMinutes (car/get-time car-state) (car/get-minutes-to-target-percent car-state target-percent)))
            (car/get-charge-rate-amps car-state))
    (printf "At max rate, will reach %s%% by %s at %sA%n"
            target-percent
            (utils/format-time "HH:mm:ss" (.plusMinutes (car/get-time car-state) (car/get-minutes-to-target-percent-at-max-rate car-state target-percent)))
            (car/get-max-charge-rate-amps car-state))
    (assert ())))

