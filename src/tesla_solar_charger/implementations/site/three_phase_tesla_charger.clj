(ns tesla-solar-charger.implementations.site.three-phase-tesla-charger
  (:require
   [tesla-solar-charger.utils :as utils]
   [tesla-solar-charger.interfaces.charger :as charger]
   [tesla-solar-charger.interfaces.car :as Icar]))

(defn get-three-phase-voltage-volts
  [charger]
  (utils/throw-if-attribute-nil charger :three-phase-voltage-volts)
  (:three-phase-voltage-volts charger))

(defn get-power-factor
  [charger]
  (utils/throw-if-attribute-nil charger :power-factor)
  (:power-factor charger))

(defn get-max-current-amps
  [charger]
  (utils/throw-if-attribute-nil charger :max-current-amps)
  (:max-current-amps charger))

(defn watts-to-amps-three-phase
  [power-watts voltage-volts power-factor]
  (/ power-watts (* voltage-volts power-factor (clojure.math/sqrt 3))))

(defn amps-to-watts-three-phase
  [current-amps voltage-volts power-factor]
  (* voltage-volts current-amps power-factor (clojure.math/sqrt 3)))

(defn watts-to-amps-three-phase-australia
  [power-watts]
  (let [voltage-volts 400
        power-factor 1.0]
    (watts-to-amps-three-phase power-watts voltage-volts power-factor)))

(defn amps-to-watts-three-phase-australia
  [current-amps]
  (let [voltage-volts 400
        power-factor 1.0]
    (amps-to-watts-three-phase current-amps voltage-volts power-factor)))

(defn convert-watts-to-amps [charger watts]
  (let [three-phase-voltage-volts (get-three-phase-voltage-volts charger)
        power-factor (get-power-factor charger)]
    (utils/power-watts-to-current-amps-three-phase watts three-phase-voltage-volts power-factor)))

(defrecord TeslaChargerThreePhase []

  charger/ICharger
  (set-car-charge-power [charger car power-watts]
    (let [current-amps (convert-watts-to-amps charger power-watts)
          result (Isite-charger/set-car-charge-current charger car current-amps)]
      result)))

(defn new-TeslaChargerThreePhase
  [three-phase-voltage-volts max-current-amps power-factor]
  (let [the-map {:three-phase-voltage-volts three-phase-voltage-volts
                 :max-current-amps max-current-amps
                 :power-factor power-factor}
        defaults {}
        charger (map->TeslaChargerThreePhase (merge defaults the-map))]
    charger))
