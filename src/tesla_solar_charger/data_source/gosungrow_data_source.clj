(ns tesla-solar-charger.data-source.gosungrow-data-source
  (:require
   [cheshire.core :as json]
   [tesla-solar-charger.data-source.data-source :refer [make-data-point IDataSource]]
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

(defn make-data-point-from-json
  [object excess-power-key]
  (let [timestamp-formatter (java.time.format.DateTimeFormatter/ofPattern "yyyyMMddHHmmss")
        zone-id (java.time.ZoneId/of "Australia/Brisbane")
        timestamp (-> object
                      (get "timestamp")
                      (java.time.LocalDateTime/parse timestamp-formatter)
                      (.atZone zone-id)
                      .toInstant)
        excess-power-watts (-> object
                               (get "points")
                               (get excess-power-key)
                               float
                               -)
        data-point (make-data-point timestamp excess-power-watts)]
    data-point))

(defn execute-shell-command
  [script-filepath ps-id ps-key excess-power-key minute-interval start-timestamp-string end-timestamp-string]
  (let [result (sh
                 script-filepath
                 "data"
                 "json"
                 "AppService.queryMutiPointDataList" 
                 (format "StartTimeStamp:%s" start-timestamp-string)
                 (format "EndTimeStamp:%s" end-timestamp-string)
                 (format "MinuteInterval:%s" (str minute-interval)) 
                 (format "Points:%s" excess-power-key) 
                 (format "PsId:%s" ps-id) 
                 (format "PsKeys:%s" ps-key))] 
    result))

(defn get-latest-data-point
  [script-filepath gosungrow-appkey sungrow-username sungrow-password ps-key ps-id ps-point]
  (let [time-now (java.time.Instant/now)
        data-interval-minutes 5
        zone-id (java.time.ZoneId/of "Australia/Brisbane")
        timestamp-formatter (java.time.format.DateTimeFormatter/ofPattern "yyyyMMddHHmmss")
        end-timestamp-string (-> time-now
                                 (.minus 1 java.time.temporal.ChronoUnit/MINUTES)
                                 (round-minutes-down-to-interval data-interval-minutes)
                                 (.atZone zone-id)
                                 (.format timestamp-formatter))
        start-timestamp-string (-> time-now
                                   (.minus 1 java.time.temporal.ChronoUnit/MINUTES)
                                   (.minus data-interval-minutes java.time.temporal.ChronoUnit/MINUTES)
                                   (round-minutes-down-to-interval data-interval-minutes)
                                   (.atZone zone-id)
                                   (.format timestamp-formatter))
        result (execute-shell-command 
                 script-filepath
                 ps-id
                 ps-key
                 ps-point
                 5
                 start-timestamp-string
                 end-timestamp-string)]
    (when (not= 0 (:exit result))
      (throw (ex-info (format "Failed to execute GoSungrow data command; %s %s" (:err result) (:out result) {}))))
    (let [stdout (:out result)
          json (json/parse-string stdout)
          data (get json "data")
          data-points (->> data
                           (map second)
                           (map #(make-data-point-from-json % ps-point)))
          latest-data-point (->> data-points
                                 (sort-by :timestamp)
                                 last)]
      (when (nil? latest-data-point)
        (throw (ex-info "No data point found" {})))
      latest-data-point)))

(defrecord GoSungrowDataSource []
  IDataSource
  (get-latest-data-point [data-source] 
    (let [script-filepath (:script-filepath data-source)
          gosungrow-appkey (:gosungrow-appkey data-source)
          sungrow-username (:sungrow-username data-source)
          sungrow-password (:sungrow-password data-source)
          ps-key (:ps-key data-source)
          ps-id (:ps-id data-source)
          ps-point (:excess-power-key data-source)
          latest-data-point (get-latest-data-point 
                              script-filepath 
                              gosungrow-appkey 
                              sungrow-username 
                              sungrow-password 
                              ps-key 
                              ps-id 
                              ps-point)]
      latest-data-point)))

(defn new-GoSungrowDataSource
  [script-filepath gosungrow-appkey sungrow-username sungrow-password ps-key ps-id excess-power-key]
  (let [the-map {:script-filepath script-filepath
                 :gosungrow-appkey gosungrow-appkey
                 :sungrow-username sungrow-username
                 :sungrow-password sungrow-password
                 :ps-key ps-key
                 :ps-id ps-id
                 :excess-power-key excess-power-key}
        defaults {}
        result (sh 
                 script-filepath
                 "config" 
                 "write" 
                 (format "--appkey=%s" gosungrow-appkey) 
                 (format "--user=%s" sungrow-username) 
                 (format "--password=%s" sungrow-password))]
    (when (not= 0 (:exit result))
      (throw (ex-info (format "Failed to config GoSungrow executable; %s %s" (:err result) (:out result) {}))))
    (map->GoSungrowDataSource (merge defaults the-map))))

