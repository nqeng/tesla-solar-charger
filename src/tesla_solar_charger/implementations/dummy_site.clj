(ns tesla-solar-charger.implementations.dummy-site
  (:require
   [tesla-solar-charger.interfaces.site :as site]
   [tesla-solar-charger.interfaces.car :as car]
   [better-cond.core :as b]
   [tesla-solar-charger.utils :as utils]))

(defn euclidean-distance
  [x1 y1 x2 y2]
  (Math/sqrt (+ (Math/pow (- x2 x1) 2) (Math/pow (- y2 y1) 2))))

(defrecord DummySiteData []

  site/SiteData

  (add-point [data point] (update data :points #(vec (conj % point))))
  (get-points [data] (:points data)))

(defrecord DummySiteDataPoint [time excess-power-watts]

  site/SiteDataPoint

  (get-time [point] (get point :time))
  (get-excess-power-watts [point] (get point :excess-power-watts)))

(defrecord DummySite [name latitude longitude]

  site/Site

  (get-name [site] name)
  (is-car-here? [site car-state]
    (< (euclidean-distance
        (car/get-latitude car-state)
        (car/get-longitude car-state)
        latitude
        longitude) 0.0005))

  (power-watts-to-current-amps [site power-watts] (/ power-watts 687.5))

  (get-data [site request]
    (try
      (b/cond
        (nil? (:start-time request))
        nil

        (nil? (:end-time request))
        nil

        :let [site (assoc site :next-data-available-time (.plusSeconds (utils/time-now) 60))]
        :let [data (-> (->DummySiteData)
                       (site/add-point (->DummySiteDataPoint (utils/time-now) 50)))]

        [site data])
      (catch clojure.lang.ExceptionInfo e
        (throw e))
      (catch Exception e
        (throw e)))))

