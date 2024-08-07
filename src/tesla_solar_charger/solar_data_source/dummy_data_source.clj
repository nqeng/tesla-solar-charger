(ns tesla-solar-charger.solar-data-source.dummy-data-source
  (:require
   [tesla-solar-charger.solar-data-source.solar-data-source :as data-source]
   [tesla-solar-charger.utils :as utils]))

(def excess-power-watts 100)

(defn get-latest-data-point
  [data-source]
  (data-source/make-data-point 
    (java.time.Instant/now) 
    excess-power-watts))

(defrecord DummyDataSource []
  data-source/IDataSource
  (get-latest-data-point [data-source] 
    (let [data-point (get-latest-data-point data-source)]
      {:obj data-source :err nil :val data-point})))

(defn new-DummyDataSource
  []
  (let [the-map {}
        defaults {}]
    (map->DummyDataSource (merge defaults the-map))))
