(ns tesla-solar-charger.interfaces.charger)

(defprotocol ICharger
  (set-car-charge-power [charger car power-watts]))
