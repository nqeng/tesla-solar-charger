(ns tesla-solar-charger.interfaces.site-charger)

(defprotocol SiteCharger
  (set-car-charge-power [charger car power-watts]))
