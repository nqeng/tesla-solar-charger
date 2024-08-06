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
        excess-power-watts (float (- (get-in object ["points" excess-power-key])))
        data-point (make-data-point time excess-power-watts)]
    data-point))

#_(sh "sh" "-c" "pwd")

(defn execute-shell-command
  [script-filepath ps-id ps-key excess-power-key minute-interval start-timestamp-str end-timestamp-str]
  (let [result (sh
          "./GoSungrow"
          "data"
          "json"
          "AppService.queryMutiPointDataList" 
          (format "StartTimeStamp:%s" start-timestamp-str)
          (format "EndTimeStamp:%s" end-timestamp-str)
          (format "MinuteInterval:%s" (str minute-interval)) 
          (format "Points:%s" excess-power-key) 
          (format "PsId:%s" ps-id) 
          (format "PsKeys:%s" ps-key))
        _ (println result)
        ;out (:out (sh "whoami"))
        ;_ (println out)
        ;out (:out (sh "pwd"))
        ;_ (println out)
        ;out (:out (sh "ls"))
        ;_ (println out)
        ] 
    result
    ;(println "1" (sh "ls"))
    ;(println "1" (sh "sh" "-c" "ls"))
    ;(println "2" (sh "whoami"))
    ;(println "16" (sh "sh" "-c" "test -e GoSungrow"))
    ;(println "17" (sh "sh" "-c" "sh ./GoSungrow"))
    #_(println "18" (sh "./GoSungrow" "data" "json"))
    #_(println "19" (sh "env"))
    #_(println "20" (sh "which" "GoSungrow"))
    #_(println "21" (sh "GoSungrow"))
    #_(println "22" (sh "sh" "-c" "/usr/local/bin/GoSungrow"))
    #_(println (-> (java.io.File. ".") .getAbsolutePath))
    #_(println (vec (file-seq (java.io.File. "."))))
    #_(println (System/getProperty "user.dir"))
    #_(println script-filepath)
    #_(println (sh "echo" "hello" "world"))
    #_(println (sh "sh" "-c" "file"))
    #_(println (sh "GoSungrow" :env {"PATH" "/tesla-solar-charger"}))
    #_(println (sh "sh" "-c" "whoami"))
    #_(println (sh "sh" "-c" "pwd"))
    #_(println (sh "sh" "-c" "\"./GoSungrow\"" :dir "/tesla-solar-charger"))
    #_(println (sh "sh" "-c" "ls -la"))
    #_(println (sh "sh" "-c" "\"./GoSungrow\""))
    #_(apply sh args)))

#_(sh "sh" "-c" "\"./GoSungrow\"")


#_(comment
  (execute-shell-command
  "./GoSungrow"
  "1152381"
  "1152381_7_2_3"
  "p8018"
  5
  "20240801080000"
  "20240801080000"
  ))

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
        result (execute-shell-command script-filepath
                                      ps-id
                                      ps-key
                                      excess-power-key
                                      5
                                      start-timestamp
                                      end-timestamp)
        stdout (:out result)
        json (json/parse-string stdout)
        data (get json "data")
        data-points (->> data
                         (map second)
                         (map #(make-data-point-from-json % excess-power-key)))
        latest-point (->> data-points
                          (sort-by :timestamp)
                          last)]
    (when (nil? latest-point)
      (throw (ex-info "No data point found" {})))
    latest-point))

(defrecord GoSungrowDataSource []

  IDataSource
  (get-latest-data-point [data-source] (get-latest-data-point data-source)))

(defn new-GoSungrowDataSource
  [script-filepath ps-key ps-id excess-power-key]
  (let [the-map {:script-filepath script-filepath
                 :ps-key ps-key
                 :ps-id ps-id
                 :excess-power-key excess-power-key}
        defaults {}]
    (map->GoSungrowDataSource (merge defaults the-map))))

