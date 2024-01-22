(ns tesla-solar-charger.implementations.site-data.sungrow-site-data
  (:require
   [tesla-solar-charger.interfaces.site-data :as Isite-data]
   [tesla-solar-charger.utils :as utils]
   [tesla-solar-charger.interfaces.site :as Isite]))

(defrecord SungrowSiteDataPoint [time excess-power-watts]

  Isite-data/SiteDataPoint

  (get-time [point] (get point :time))
  (get-excess-power-watts [point] (get point :excess-power-watts))

  utils/Printable
  (string-this [this] (format "%s -> %s" (utils/format-time (Isite-data/get-time this)) (str (dissoc (into {} this) :time))))
  (print-this [this] (println (utils/string-this this))))

(defn new-SungrowSiteDataPoint
  [time excess-power]
  (let [the-map {:time time
                 :excess-power-watts excess-power}
        defaults {}]
    (map->SungrowSiteDataPoint (merge defaults the-map))))

(defrecord SungrowSiteData []

  Isite-data/SiteData

  (is-newer? [data other-data]
    (cond
      (or (nil? data) (empty? (Isite-data/get-points data)))
      false

      (or (nil? other-data) (empty? (Isite-data/get-points other-data)))
      true

      :else
      (.isAfter
       (Isite-data/get-time (last (Isite-data/get-points data)))
       (Isite-data/get-time (last (Isite-data/get-points other-data))))))
  (add-point [data time excess-power]
    (let [point (new-SungrowSiteDataPoint time excess-power)]
      (update data :points #(vec (conj % point)))))
  (get-points [data] (:points data))
  (get-latest-point [data] (last (Isite-data/get-points data)))
  (get-latest-excess-power-watts [data] (Isite-data/get-excess-power-watts (Isite-data/get-latest-point data)))
  (get-latest-time [data] (Isite-data/get-time (Isite-data/get-latest-point data)))

  utils/Printable

  (string-this [this] (format "Latest excess power is %s as of %s"
                              (Isite-data/get-latest-excess-power-watts this)
                              (utils/format-time (Isite-data/get-latest-time this))))
  (print-this [this] (println (utils/string-this this))))

(defn new-SungrowSiteData
  []
  (let [the-map {}
        defaults {}]
    (map->SungrowSiteData (merge defaults the-map))))

(comment
  (let [data (-> (new-SungrowSiteData)
                 (Isite-data/add-point (utils/time-now) 0)
                 (Isite-data/add-point (utils/time-now) 0)
                 (Isite-data/add-point (utils/time-now) 0)
                 (Isite-data/add-point (utils/time-now) 0))]
    (utils/print-this data)
    (utils/string-this data)))
