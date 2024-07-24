(ns tesla-solar-charger.data-source.gosungrow-data-source
  (:require
   [cheshire.core :as json]
   [tesla-solar-charger.data-source.data-source :as data-source]
   [clojure.java.shell :refer [sh]]
   [tesla-solar-charger.utils :as utils]))

(defn round-minutes-up-to-interval
  [instant interval-minutes]
  (let [minutes (int (/ (.getEpochSecond instant) 60))
        mod-minutes (mod minutes interval-minutes)
        rounded-minutes (+ minutes (- interval-minutes mod-minutes))
        rounded-time (java.time.Instant/ofEpochSecond (* 60 rounded-minutes))]
    rounded-time))

(defn round-minutes-down-to-interval
  [instant interval-minutes]
  (let [minutes (int (/ (.getEpochSecond instant) 60))
        mod-minutes (mod minutes interval-minutes)
        rounded-minutes (- minutes mod-minutes)
        rounded-time (java.time.Instant/ofEpochSecond (* 60 rounded-minutes))]
    rounded-time))

(defn format-timestamp1
  [instant]
  (.format (.atZone instant (java.time.ZoneId/systemDefault)) (java.time.format.DateTimeFormatter/ofPattern "yyyyMMddHHmmss")))

(defn parse-timestamp1
  [time-str]
  (java.time.LocalDateTime/parse time-str (java.time.format.DateTimeFormatter/ofPattern "yyyyMMddHHmmss")))

(defn make-data-point-from-json
  [object excess-power-key]
  (let [time-string (get object "timestamp")
        time (.toInstant (.atZone (parse-timestamp1 time-string) (java.time.ZoneId/systemDefault)))
        excess-power-watts (get-in object ["points" excess-power-key])
        data-point (data-source/make-data-point time excess-power-watts)]
    data-point))

(defn get-latest-data-point
  [data-source]
  (let [script-filepath (:script-filepath data-source)
        ps-key (:ps-key data-source)
        ps-id (:ps-id data-source)
        excess-power-key (:excess-power-key data-source)
        time-now (.minusSeconds (utils/time-now) 60)
        rounded (round-minutes-down-to-interval time-now 5)
        start-timestamp (format-timestamp1 (.minusSeconds rounded (* 5 60)))
        end-timestamp (format-timestamp1 rounded)
        data (-> (sh
                  script-filepath
                  ps-key
                  ps-id
                  excess-power-key
                  (str 5)
                  start-timestamp
                  end-timestamp)
                 :out
                 json/parse-string
                 (get "data"))
        data-points (->> data
                         (map second)
                         (map #(make-data-point-from-json % excess-power-key)))
        latest-point (->> data-points
                          (sort-by :timestamp)
                          last)]
    latest-point))

(defrecord GoSungrowDataSource []

  data-source/IDataSource
  (get-latest-data-point [data-source] (get-latest-data-point data-source)))

(defn new-GoSungrowDataSource
  [script-filepath ps-key ps-id excess-power-key]
  (let [the-map {:script-filepath script-filepath
                 :ps-key ps-key
                 :ps-id ps-id
                 :excess-power-key excess-power-key}
        defaults {}]
    (map->GoSungrowDataSource (merge defaults the-map))))

