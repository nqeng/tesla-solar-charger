(ns tesla-solar-charger.data-source.data-source)

(defprotocol IDataSource
  (get-latest-data-point [data-source]))

(defn make-data-point
  [timestamp excess-power-watts]
  {:timestamp timestamp
   :excess-power-watts excess-power-watts})

