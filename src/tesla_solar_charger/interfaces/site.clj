(ns tesla-solar-charger.interfaces.site)

(defprotocol Site
  (get-name [site])
  (get-id [site])
  (power-watts-to-current-amps [site power-watts])
  (is-car-here? [site car-state])
  (get-data [site request]))

(defprotocol SiteData
  (add-point [data point])
  (get-points [data]))

(defprotocol SiteDataPoint
  (get-time [point])
  (get-excess-power-watts [point]))
