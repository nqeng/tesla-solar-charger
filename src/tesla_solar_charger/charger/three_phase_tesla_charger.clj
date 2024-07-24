(ns tesla-solar-charger.charger.three-phase-tesla-charger
  (:require
   [tesla-solar-charger.charger.charger :as charger]
   [tesla-solar-charger.car.car :as car]))

(def voltage 400)
(def power-factor 1.0)

(defn watts-to-amps-three-phase
  [power-watts voltage-volts power-factor]
  (/ power-watts (* voltage-volts power-factor (clojure.math/sqrt 3))))

(defn amps-to-watts-three-phase
  [current-amps voltage-volts power-factor]
  (* voltage-volts current-amps power-factor (clojure.math/sqrt 3)))

(defn convert-amps-to-watts
  [current-amps]
  (amps-to-watts-three-phase current-amps power-factor voltage))

(defn convert-watts-to-amps
  [power-watts]
  (watts-to-amps-three-phase power-watts voltage power-factor))

(defn get-car-charge-power-watts
  [charger car-state]
  (let [charge-current-amps (:charge-current-amps car-state)
        charge-power-watts (convert-watts-to-amps charge-current-amps)]
    charge-power-watts))

(defn set-car-charge-power-watts
  [charger car power-watts]
  (let [charge-power-amps (convert-amps-to-watts power-watts)]
    (car/set-charge-current car charge-power-amps)))

(defn get-max-car-charge-power-watts
  [charger car-state]
  (let [max-charge-current-amps (:max-charge-current-amps car-state)
        max-charge-power-watts (convert-amps-to-watts max-charge-current-amps)]
    max-charge-power-watts))

(defrecord TeslaChargerThreePhase []
  charger/ICharger
  (get-max-car-charge-power-watts [charger car-state] (get-max-car-charge-power-watts charger car-state))
  (get-car-charge-power-watts [charger car-state] (get-car-charge-power-watts charger car-state))
  (set-car-charge-power-watts [charger car power-watts] (set-car-charge-power-watts charger car power-watts)))

(defn new-TeslaChargerThreePhase
  []
  (let [the-map {}
        defaults {}
        charger (map->TeslaChargerThreePhase (merge defaults the-map))]
    charger))
