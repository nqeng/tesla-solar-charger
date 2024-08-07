(ns tesla-solar-charger.solar-data-source.gosungrow-data-source
  (:require
   [cheshire.core :as json]
   [tesla-solar-charger.log :as log]
   [tesla-solar-charger.solar-data-source.solar-data-source :refer [make-data-point IDataSource]]
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
  [object ps-point]
  (let [timestamp-formatter (java.time.format.DateTimeFormatter/ofPattern "yyyyMMddHHmmss")
        zone-id (java.time.ZoneId/of "Australia/Brisbane")
        timestamp (-> object
                      (get "timestamp")
                      (java.time.LocalDateTime/parse timestamp-formatter)
                      (.atZone zone-id)
                      .toInstant)
        excess-power-watts (-> object
                               (get "points")
                               (get ps-point)
                               float
                               -)
        data-point (make-data-point timestamp excess-power-watts)]
    data-point))

(defn execute-config-shell-command
  [gosungrow-filepath appkey username password]
  (sh 
    gosungrow-filepath
    "config" 
    "write" 
    (format "--appkey=%s" appkey) 
    (format "--user=%s" username) 
    (format "--password=%s" password)))

(defn configure-gosungrow-executable
  [gosungrow-filepath appkey username password]
  (let [result (execute-config-shell-command gosungrow-filepath appkey username password)]
    (when (not= 0 (:exit result))
      (throw (ex-info 
               (format "GoSungrow config write exited with code %d; %s %s" 
                       (:exit result) 
                       (:err result) 
                       (:out result)) 
               {})))))

(defn execute-data-shell-command
  [gosungrow-filepath ps-id ps-key ps-point data-interval-minutes start-timestamp-string end-timestamp-string]
  (sh
    gosungrow-filepath
    "data"
    "json"
    "AppService.queryMutiPointDataList" 
    (format "StartTimeStamp:%s" start-timestamp-string)
    (format "EndTimeStamp:%s" end-timestamp-string)
    (format "MinuteInterval:%s" (str data-interval-minutes)) 
    (format "Points:%s" ps-point) 
    (format "PsId:%s" ps-id) 
    (format "PsKeys:%s" ps-key)))

(defn get-gosungrow-json-data
  [gosungrow-filepath ps-id ps-key ps-point data-interval-minutes start-timestamp-string end-timestamp-string]
  (let [result (execute-data-shell-command 
                 gosungrow-filepath
                 ps-id
                 ps-key
                 ps-point
                 data-interval-minutes
                 start-timestamp-string
                 end-timestamp-string)]
    (when (not= 0 (:exit result))
      (throw (ex-info 
               (format "GoSungrow data json exited with code %d; %s %s" 
                       (:exit result) 
                       (:out result) 
                       (:err result)) 
               {})))
    (json/parse-string (:out result))))

(defn get-latest-data-point
  [gosungrow-filepath appkey username password ps-key ps-id ps-point]
  (configure-gosungrow-executable gosungrow-filepath appkey username password)
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
        json-object (get-gosungrow-json-data
                      gosungrow-filepath 
                      ps-id 
                      ps-key 
                      ps-point 
                      data-interval-minutes 
                      start-timestamp-string 
                      end-timestamp-string)
        data (get json-object "data")
        data-points (->> data
                         (map second)
                         (map #(make-data-point-from-json % ps-point)))
        latest-data-point (->> data-points
                               (sort-by :timestamp)
                               last)]
    (when (nil? latest-data-point)
      (throw (ex-info "No data point found" {})))
    latest-data-point))

(defrecord GoSungrowDataSource []
  IDataSource
  (get-latest-data-point [data-source] 
    (try
      (let [gosungrow-filepath (:gosungrow-filepath data-source)
            appkey (:appkey data-source)
            username (:username data-source)
            password (:password data-source)
            ps-key (:ps-key data-source)
            ps-id (:ps-id data-source)
            ps-point (:ps-point data-source)
            latest-data-point (get-latest-data-point 
                                gosungrow-filepath 
                                appkey 
                                username 
                                password 
                                ps-key 
                                ps-id 
                                ps-point)]
        {:obj data-source :err nil :val latest-data-point})
      (catch clojure.lang.ExceptionInfo err
        {:obj data-source :err err :val nil})
      (catch Exception err
        {:obj data-source :err err :val nil}))))

(defn new-GoSungrowDataSource
  [gosungrow-filepath appkey username password ps-key ps-id ps-point]
  (let [the-map {:gosungrow-filepath gosungrow-filepath
                 :appkey appkey
                 :username username
                 :password password
                 :ps-key ps-key
                 :ps-id ps-id
                 :ps-point ps-point}
        defaults {}]
    (map->GoSungrowDataSource (merge defaults the-map))))

