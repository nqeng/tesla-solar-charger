(ns tesla-solar-charger.interfaces.site)

(defprotocol Site
  (is-car-here? [site car-state]))

