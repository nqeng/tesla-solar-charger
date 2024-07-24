(ns tesla-solar-charger.implementations.site-data.dummy-data-source
  (:require
   [tesla-solar-charger.implementations.site-data.sungrow-site-data :refer [new-SungrowSiteData]]
   [tesla-solar-charger.interfaces.site :as Isite]
   [tesla-solar-charger.interfaces.site-data :as Isite-data]
   [tesla-solar-charger.utils :as utils]))

(defrecord DummyDataSource []

  Isite-data/SiteDataSource

  (get-latest-data-point [data-source]
    (let [excess-power-watts 100
          data (Isite-data/make-data-point (utils/time-now) excess-power-watts)]
      data))
  (when-next-data-point-available?
    [data-source last-data-point]
    (.plusSeconds (:timestamp last-data-point) 30)))

(defn new-DummyDataSource
  []
  (let [the-map {}
        defaults {}]
    (map->DummyDataSource (merge defaults the-map))))
