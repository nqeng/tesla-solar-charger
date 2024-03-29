(ns tesla-solar-charger.implementations.site.three-phase-tesla-charger
  (:require
   [tesla-solar-charger.utils :as utils]
   [tesla-solar-charger.interfaces.site-charger :as Isite-charger]
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

(defn convert-watts-to-amps [charger watts]
  (let [three-phase-voltage-volts (get-three-phase-voltage-volts charger)
        power-factor (get-power-factor charger)]
    (utils/power-watts-to-current-amps-three-phase watts three-phase-voltage-volts power-factor)))

(defn convert-amps-to-watts [charger amps] 0)

(defrecord TeslaChargerThreePhase []

  Isite-charger/SiteCharger

  (set-car-charge-power [charger car power-watts]
    (let [current-amps (convert-watts-to-amps charger power-watts)
          result (Isite-charger/set-car-charge-current charger car current-amps)]
      result))
  (set-car-charge-current [charger car current-amps]
    (let [charger-max-current-amps (get-max-current-amps charger)
          current-amps (utils/clamp-min-max current-amps 0 charger-max-current-amps)]
      (Icar/set-charge-current car current-amps)))
  (get-car-max-charge-power-watts [charger car-state]
    (let [max-charge-current-amps (Icar/get-max-charge-current-amps car-state)
          max-charge-power-watts (convert-amps-to-watts charger max-charge-current-amps)]
      max-charge-power-watts))
  (get-car-charge-power-watts [charger car-state]
    (let [charge-current-amps (Icar/get-charge-current-amps car-state)
          charge-power-watts (convert-amps-to-watts charger charge-current-amps)]
      charge-power-watts))
  (adjust-car-charge-power [charger car power-adjustment-watts]
    (let [car-state (Icar/get-state car)
          charge-power-watts (Isite-charger/get-car-charge-power-watts charger car-state)
          new-charge-power-watts (+ charge-power-watts power-adjustment-watts)]
      (Isite-charger/set-car-charge-power charger car new-charge-power-watts)))
  (is-car-connected-here? [charger car-state] (Icar/is-connected? car-state))
  (is-car-charging-here? [charger car-state] (Icar/is-charging? car-state)))

(defn new-TeslaChargerThreePhase
  [three-phase-voltage-volts max-current-amps power-factor]
  (let [the-map {:three-phase-voltage-volts three-phase-voltage-volts
                 :max-current-amps max-current-amps
                 :power-factor power-factor}
        defaults {}
        charger (map->TeslaChargerThreePhase (merge defaults the-map))]
    charger))
