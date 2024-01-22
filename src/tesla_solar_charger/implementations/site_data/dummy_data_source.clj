(ns tesla-solar-charger.implementations.site-data.dummy-data-source
  (:require
   [tesla-solar-charger.implementations.site-data.sungrow-site-data :refer [new-SungrowSiteData]]
   [tesla-solar-charger.interfaces.site :as Isite]
   [tesla-solar-charger.interfaces.site-data :as Isite-data]
   [tesla-solar-charger.utils :as utils]))

(defrecord DummyDataSource []

  Isite-data/SiteDataSource

  (get-data [data-source request]
    (let [excess-power-watts 100
          data (-> (new-SungrowSiteData)
                   (Isite-data/add-point (utils/time-now) excess-power-watts))]
      [data-source data]))
  (when-next-data-ready? [data-source] (utils/time-after-seconds 10)))

(defn new-DummyDataSource
  []
  (let [the-map {}
        defaults {:next-data-available-time (utils/time-now)}]
    (map->DummyDataSource (merge defaults the-map))))
