(ns tesla-solar-charger.interfaces.site-data)

(defprotocol SiteDataSource
  (get-latest-data-point [data-source]))

(defn make-data-point
  [timestamp excess-power-watts]
  {:timestamp timestamp
   :excess-power-watts excess-power-watts})

(defn is-newer?
  [data1 data2]
  (.isAfter (:timestamp data1) (:timestamp data2)))

