(ns tesla-solar-charger.interfaces.site-charger)

(defprotocol ICharger
  (set-car-charge-power [charger car power-watts]))
