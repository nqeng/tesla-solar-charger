(ns tesla-solar-charger.implementations.car.dummy-tesla
  (:require
   [tesla-solar-charger.interfaces.car :as Icar]
   [tesla-solar-charger.interfaces.site :as Isite]
   [tesla-solar-charger.utils :as utils]
   [cheshire.core :as json]
   [tesla-solar-charger.interfaces.site-charger :as Isite-charger]))

(defn get-charge-status
  [state]
  (get-in (:object state) ["charge_state" "charging_state"]))

(defrecord DummyTeslaState []

  Icar/CarState

  (get-time [state] (utils/time-from-epoch-millis (get-in (:object state) ["charge_state" "timestamp"])))
  (get-id [state] (str (utils/time-now)))
  (is-newer? [state other-state]
    (cond
      (nil? state) false
      (nil? other-state) true
      :else (.isAfter (Icar/get-time state) (Icar/get-time other-state))))
  (is-charging? [state] (= "Charging" (get-charge-status state)))
  (is-connected? [state] (not= "Disconnected" (get-charge-status state)))
  (is-override-active? [state] (true? (get-in (:object state) ["vehicle_state" "valet_mode"])))
  (get-charge-current-amps [state] (get-in (:object state) ["charge_state" "charge_amps"]))
  (get-charge-limit-percent [state] (get-in (:object state) ["charge_state" "charge_limit_soc"]))
  (get-minutes-to-full-charge [state] (get-in (:object state) ["charge_state" "minutes_to_full_charge"]))
  (get-minutes-to-target-percent [state target-percent]
    (let [charge-limit-percent (Icar/get-charge-limit-percent state)
          minutes-to-full-charge (Icar/get-minutes-to-full-charge state)
          minutes-per-percent (/ minutes-to-full-charge charge-limit-percent)
          minutes-to-target-percent (* minutes-per-percent target-percent)]
      minutes-to-target-percent))
  (get-minutes-to-target-percent-at-max-rate [state target-percent]
    (let [charge-rate-amps (Icar/get-charge-current-amps state)
          max-charge-rate-amps (Icar/get-max-charge-current-amps state)
          minutes-to-target-percent (Icar/get-minutes-to-target-percent state target-percent)
          minutes-per-amp (/ minutes-to-target-percent max-charge-rate-amps)
          minutes-to-target-percent-at-max-rate (* minutes-per-amp charge-rate-amps)]
      minutes-to-target-percent-at-max-rate))
  (get-max-charge-current-amps [state] (get-in (:object state) ["charge_state" "charge_current_request_max"]))
  (will-reach-target-by? [state target-percent target-time]
    (< (Icar/get-minutes-to-target-percent state target-percent)
       (utils/calc-minutes-between-times (Icar/get-time state) target-time)))
  (should-override-to-reach-target?
    [state target-percent target-time]
    (let [minutes-to-target-percent-at-max-rate (Icar/get-minutes-to-target-percent-at-max-rate state target-percent)
          minutes-left-to-charge (utils/calc-minutes-between-times (Icar/get-time state) target-time)]
      (< minutes-left-to-charge minutes-to-target-percent-at-max-rate)))
  (get-latitude [state] (get-in (:object state) ["drive_state" "latitude"]))
  (get-longitude [state] (get-in (:object state) ["drive_state" "longitude"])))

(defn new-DummyTeslaState
  [object]
  (let [the-map {:object object}
        defaults {}
        state (map->DummyTeslaState (merge defaults the-map))]
    state))

(def default-state
  (new-DummyTeslaState
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
     "charge_limit_soc_max" 100}}))

(defn read-car-state-from-file
  [car]
  (let [filename (-> car
                     Icar/get-vin
                     (str ".json"))
        contents (slurp filename)
        json (json/parse-string contents)
        state (new-DummyTeslaState json)]
    state))

(defn write-car-state-to-file
  [car car-state]
  (let [filename (-> car
                     Icar/get-vin
                     (str ".json"))
        json (json/generate-string car-state {:pretty true})]
    (spit filename json)))

(defrecord DummyTesla []

  Icar/Car

  (get-state [car]
    (let [new-state (try
                      (read-car-state-from-file car)
                      (catch Exception e default-state))
          epoch-seconds-now (* 1000 (.getEpochSecond (java.time.Instant/now)))
          new-state (assoc-in new-state [:object "charge_rate" "timestamp"] epoch-seconds-now)]
      [car new-state]))

  (get-vin [car] (utils/throw-if-attribute-nil car :vin) (:vin car))
  (get-name [car] "Dummy")
  (set-charge-current [car current-amps]
    (let [car-state (Icar/get-state car)
          max-charge-current-amps (Icar/get-max-charge-current-amps car-state)
          new-charge-current-amps (utils/clamp-min-max current-amps 0 max-charge-current-amps)
          new-state (assoc-in car-state [:object "charge_state" "charge_amps"] new-charge-current-amps)]
      (write-car-state-to-file car new-state)))
  (restore-this-state [car state-to-restore]
    (let [charge-rate-amps (Icar/get-charge-current-amps state-to-restore)]
      (Icar/set-charge-current car charge-rate-amps))))

(defn new-DummyTesla
  [vin]
  (let [the-map {:vin vin}
        defaults {}
        car (map->DummyTesla (merge defaults the-map))]
    car))

