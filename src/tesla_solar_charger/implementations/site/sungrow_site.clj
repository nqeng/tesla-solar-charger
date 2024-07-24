(ns tesla-solar-charger.implementations.site.sungrow-site
  (:require
   [tesla-solar-charger.utils :as utils]
   [tesla-solar-charger.interfaces.site-data :as Isite-data]
   [tesla-solar-charger.interfaces.site-charger :as Isite-charger]
   [tesla-solar-charger.interfaces.site :as Isite]))

(defn is-car-within-range?
  [site car-state]
  (let [range-meters (:range-meters site)
        distance-between-meters (utils/distance-between-geo-points-meters
                                 (:latitude car-state)
                                 (:longitude car-state)
                                 (:latitude site)
                                 (:longitude site))]
    (< distance-between-meters range-meters)))

(defrecord SungrowSite []

  Isite/Site

  (is-car-here? [site car-state] (is-car-within-range? site car-state))
  (is-car-connected-here? [site car-state]
    (let [charger (:charger site)]
      (and (Isite/is-car-here? site car-state)
           (Isite-charger/is-car-connected-here? charger car-state))))
  (is-car-charging-here? [site car-state]
    (let [charger (:charger site)]
      (and (Isite/is-car-here? site car-state)
           (Isite-charger/is-car-charging-here? charger car-state))))
  (did-car-start-charging-here? [site current-car-state previous-car-state]
    (cond
      (nil? current-car-state) false

      (nil? previous-car-state) true

      :else
      (and (Isite/is-car-charging-here? site current-car-state)
           (not (Isite/is-car-charging-here? site previous-car-state)))))
  (did-car-stop-charging-here? [site current-car-state previous-car-state]
    (cond
      (nil? previous-car-state) false

      (nil? current-car-state) true

      :else
      (and (not (Isite/is-car-charging-here? site current-car-state))
           (Isite/is-car-charging-here? site previous-car-state)))))

(defn new-SungrowSite
  [id name latitude longitude detection-range-meters data-source]
  (let [the-map {:id id
                 :name name
                 :latitude latitude
                 :longitude longitude
                 :range-meters detection-range-meters
                 :data-source data-source}
        defaults {}
        site (map->SungrowSite (merge defaults the-map))]
    site))


