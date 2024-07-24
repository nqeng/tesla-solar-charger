(ns tesla-solar-charger.data-source.dummy-data-source
  (:require
   [tesla-solar-charger.interfaces.site-data :as site-data]
   [tesla-solar-charger.utils :as utils]))

(defn get-latest-data-point
  [data-source]
  (let [excess-power-watts 100
        data (site-data/make-data-point (utils/time-now) excess-power-watts)]
    data))

(defrecord DummyDataSource []

  site-data/IDataSource
  (get-latest-data-point [data-source] (get-latest-data-point data-source)))

(defn new-DummyDataSource
  []
  (let [the-map {}
        defaults {}]
    (map->DummyDataSource (merge defaults the-map))))
