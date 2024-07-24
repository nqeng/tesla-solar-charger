(ns tesla-solar-charger.data-source.dummy-data-source
  (:require
   [tesla-solar-charger.data-source.data-source :as data-source]
   [tesla-solar-charger.utils :as utils]))

(defn get-latest-data-point
  [data-source]
  (let [excess-power-watts 100
        data (data-source/make-data-point (utils/time-now) excess-power-watts)]
    data))

(defrecord DummyDataSource []

  data-source/IDataSource
  (get-latest-data-point [data-source] (get-latest-data-point data-source)))

(defn new-DummyDataSource
  []
  (let [the-map {}
        defaults {}]
    (map->DummyDataSource (merge defaults the-map))))
