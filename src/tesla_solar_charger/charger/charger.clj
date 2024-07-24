(ns tesla-solar-charger.charger.charger)

(defprotocol ICharger
  (set-car-charge-power [charger car power-watts]))
