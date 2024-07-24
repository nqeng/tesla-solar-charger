(ns tesla-solar-charger.interfaces.site-charger)

(defprotocol SiteCharger
  (set-car-charge-power [charger car power-watts])
  (set-car-charge-current [charger car current-amps])
  (get-car-charge-power-watts [charger car-state])
  (get-car-max-charge-power-watts [charger car-state])
  (adjust-car-charge-power [charger car power-adjustment-watts])
  (is-car-connected-here? [charger car-state])
  (is-car-charging-here? [charger car-state]))
