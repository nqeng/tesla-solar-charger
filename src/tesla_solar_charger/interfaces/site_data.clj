(ns tesla-solar-charger.interfaces.site-data)

(defprotocol IDataSource
  (get-latest-data-point [data-source]))

(defn make-data-point
  [timestamp excess-power-watts]
  {:timestamp timestamp
   :excess-power-watts excess-power-watts})

