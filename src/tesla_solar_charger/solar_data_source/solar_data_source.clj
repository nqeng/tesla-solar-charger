(ns tesla-solar-charger.solar-data-source.solar-data-source)

(defprotocol IDataSource
  (get-latest-data-point [data-source]))

(defn make-data-point
  [timestamp excess-power-watts]
  {:timestamp timestamp
   :excess-power-watts excess-power-watts})

