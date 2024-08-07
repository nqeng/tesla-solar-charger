(ns tesla-solar-charger.car-data-source.dummy-data-source
  (:require
   [tesla-solar-charger.car-data-source.car-data-source :refer [ICarDataSource make-car-state]]))

(defn get-car-state
  []
  (make-car-state
   (java.time.Instant/now)
   false
   false
   false
   80
   0
   11000
   11000
   0
   0
   ""))

(defrecord DummyTesla []
  ICarDataSource
  (get-latest-car-state [data-source] {:obj data-source :err nil :val (get-car-state)}))

(defn new-DummyTesla
  []
  (let [the-map {}
        defaults {}
        data-source (map->DummyTesla (merge defaults the-map))]
    data-source))

