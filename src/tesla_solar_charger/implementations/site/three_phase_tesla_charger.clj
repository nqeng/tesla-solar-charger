(ns tesla-solar-charger.implementations.site.three-phase-tesla-charger
  (:require
   [tesla-solar-charger.interfaces.charger :as charger]
   [tesla-solar-charger.car.car :as car]))

(defn watts-to-amps-three-phase
  [power-watts voltage-volts power-factor]
  (/ power-watts (* voltage-volts power-factor (clojure.math/sqrt 3))))

(defn amps-to-watts-three-phase
  [current-amps voltage-volts power-factor]
  (* voltage-volts current-amps power-factor (clojure.math/sqrt 3)))

(defn set-car-charge-power
  [charger car power-watts]
  (let [voltage 400
        power-factor 1.0
        current-amps (watts-to-amps-three-phase power-watts voltage power-factor)
        result (car/set-charge-current car current-amps)]
    result))

(defrecord TeslaChargerThreePhase []

  charger/ICharger
  (set-car-charge-power [charger car power-watts] (set-car-charge-power charger car power-watts)))

(defn new-TeslaChargerThreePhase
  []
  (let [the-map {}
        defaults {}
        charger (map->TeslaChargerThreePhase (merge defaults the-map))]
    charger))
