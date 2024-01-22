(ns tesla-solar-charger.implementations.site.sungrow-site
  (:require
   [tesla-solar-charger.utils :as utils]
   [tesla-solar-charger.interfaces.car :as Icar]
   [tesla-solar-charger.interfaces.site-data :as Isite-data]
   [tesla-solar-charger.interfaces.site-charger :as Isite-charger]
   [tesla-solar-charger.interfaces.site :as Isite]))

(defn get-latitude
  [site]
  (utils/throw-if-attribute-nil site :latitude)
  (:latitude site))

(defn get-longitude
  [site]
  (utils/throw-if-attribute-nil site :longitude)
  (:longitude site))

(defn get-range-meters
  [site]
  (utils/throw-if-attribute-nil site :range-meters)
  (:range-meters site))

(defn get-data-source
  [site]
  (utils/throw-if-attribute-nil site :data-source)
  (:data-source site))

(defn is-car-within-range?
  [site car-state]
  (let [range-meters (get-range-meters site)
        car-location {:lat (Icar/get-latitude car-state) :lng (Icar/get-longitude car-state)}
        site-location {:lat (get-latitude site) :lng (get-longitude site)}
        distance-between-meters (utils/distance-between-geo-points-meters car-location site-location)]
    (< distance-between-meters range-meters)))

(defrecord SungrowSite []

  Isite/Site

  (get-name [site] (utils/throw-if-attribute-nil site :name) (:name site))
  (get-id [site] (utils/throw-if-attribute-nil site :id) (:id site))
  (get-charger [site] (utils/throw-if-attribute-nil site :charger) (:charger site))
  (is-car-here? [site car-state] (is-car-within-range? site car-state))
  (is-car-connected-here? [site car-state]
    (let [charger (Isite/get-charger site)]
      (and (Isite/is-car-here? site car-state)
           (Isite-charger/is-car-connected-here? charger car-state))))
  (is-car-charging-here? [site car-state]
    (let [charger (Isite/get-charger site)]
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
           (Isite/is-car-charging-here? site previous-car-state))))
  (did-car-connect-here? [site current-car-state previous-car-state]
    (cond
      (nil? current-car-state) false

      (nil? previous-car-state) true

      :else
      (and (Isite/is-car-connected-here? site current-car-state)
           (not (Isite/is-car-connected-here? site previous-car-state)))))
  (did-car-disconnect-here? [site current-car-state previous-car-state]
    (cond
      (nil? previous-car-state) false

      (nil? current-car-state) true

      :else
      (and (not (Isite/is-car-connected-here? site current-car-state))
           (Isite/is-car-connected-here? site previous-car-state))))
  (did-car-enter-here? [site current-car-state previous-car-state]
    (cond
      (nil? current-car-state) false

      (nil? previous-car-state) true

      :else
      (and (Isite/is-car-here? site current-car-state)
           (not (Isite/is-car-here? site previous-car-state)))))
  (did-car-leave-here? [site current-car-state previous-car-state]
    (cond
      (nil? previous-car-state) false

      (nil? current-car-state) true

      :else
      (and (not (Isite/is-car-here? site current-car-state))
           (Isite/is-car-here? site previous-car-state))))
  (with-data-source [site data-source] (assoc site :data-source data-source))
  (when-next-data-ready? [site] (Isite-data/when-next-data-ready? (get-data-source site)))
  (get-data [site request]
    (let [data-source (get-data-source site)
          [data-source data] (Isite-data/get-data data-source request)
          site (assoc site :data-source data-source)]
      [site data])))

(defn new-SungrowSite
  [id name {latitude :lat longitude :lng} range-meters]
  (let [the-map {:id id
                 :name name
                 :latitude latitude
                 :longitude longitude
                 :range-meters range-meters}
        defaults {:data-source nil}
        site (map->SungrowSite (merge defaults the-map))]
    site))


