(ns tesla-solar-charger.charger.charger)

(defprotocol ICharger
  (get-max-car-charge-power-watts [charger car-state])
  (get-car-charge-power-watts [charger car-state])
  (set-car-charge-power-watts [charger car power-watts]))

