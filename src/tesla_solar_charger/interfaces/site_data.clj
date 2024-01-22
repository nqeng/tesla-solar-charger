(ns tesla-solar-charger.interfaces.site-data)

(defprotocol SiteDataSource
  (when-next-data-ready? [data-source])
  (get-data [data-source request]))

(defprotocol SiteData
  (add-point [data time excess-power])
  (get-latest-point [data])
  (get-latest-excess-power-watts [data])
  (get-latest-time [data])
  (is-newer? [data old-data])
  (get-points [data]))

(defprotocol SiteDataPoint
  (get-time [point])
  (get-excess-power-watts [point]))
