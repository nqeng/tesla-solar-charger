(ns tesla-solar-charger.utils
  (:require
   [clj-http.client :as client]
   [clojure.math :refer [sqrt]]
   [tesla-solar-charger.haversine :refer [haversine]]
   [clojure.core.async :as async])
  (:import
   (java.time.temporal ChronoUnit)
   (java.time LocalDateTime)
   (java.time.format DateTimeFormatter)))

(defn try-return-error
  [foo]
  (try
    [nil (foo)]
    (catch Exception e
      [e nil])
    (catch clojure.lang.ExceptionInfo e
      [e nil])))

(defn watts-to-amps-three-phase
  [power-watts voltage-volts power-factor]
  (/ power-watts (* voltage-volts power-factor (sqrt 3))))

(defn amps-to-watts-three-phase
  [current-amps voltage-volts power-factor]
  (* voltage-volts current-amps power-factor (sqrt 3)))

(defn watts-to-amps-three-phase-australia
  [power-watts]
  (let [voltage-volts 400
        power-factor 1.0]
    (watts-to-amps-three-phase power-watts voltage-volts power-factor)))

(defn amps-to-watts-three-phase-australia
  [current-amps]
  (let [voltage-volts 400
        power-factor 1.0]
    (amps-to-watts-three-phase current-amps voltage-volts power-factor)))

(defn euclidean-distance
  [x1 y1 x2 y2]
  (Math/sqrt (+ (Math/pow (- x2 x1) 2) (Math/pow (- y2 y1) 2))))

(def distance-between-geo-points-kilometers haversine)

(defn distance-between-geo-points-meters
  [lat1 lng1 lat2 lng2]
  (* 1000 (distance-between-geo-points-kilometers {:lng lng1 :lat lat1} {:lng lng2 :lat lat2})))

(defn clamp-min-max
  [num min max]
  (cond
    (> num max) max
    (< num min) min
    :else       num))

(defprotocol Printable
  (string-this [thing])
  (print-this [thing]))

(defn throw-if-attribute-nil
  [map key]
  (when (nil? (get map key))
    (throw (ex-info (format "Attribute %s of map %s was nil" key map) {:type :attribute-nil}))))

(defn local-time-from-epoch-millis
  [millis]
  (java.time.LocalDateTime/ofEpochSecond (long (/ millis 1000)) 0 java.time.ZoneOffset/UTC))

(defn time-from-epoch-millis
  [millis]
  (java.time.Instant/ofEpochMilli millis))

(defn parse-local-time
  [time-str format-str]
  (java.time.LocalDateTime/parse time-str (java.time.format.DateTimeFormatter/ofPattern format-str)))

(defn minutes-between-times
  [start end]
  (.until start end ChronoUnit/MINUTES))

(defn seconds-between-times
  [start end]
  (.until start end ChronoUnit/SECONDS))

(defn millis-between-times
  [start end]
  (.until start end ChronoUnit/MILLIS))

(defn format-time
  ([format-str time]
   (.format (.atZone time (java.time.ZoneId/systemDefault)) (DateTimeFormatter/ofPattern format-str)))
  ([time]
   (format-time "yyyy-MM-dd HH:mm:ss" time)))

(defn local-now
  []
  (LocalDateTime/now))

(defn time-now
  []
  (java.time.Instant/now))

(defn time-after-seconds
  [seconds]
  (.plusSeconds (local-now) seconds))

(defn sleep-until
  [time]
  (let [time-now (time-now)]
    (when (.isAfter time time-now)
      (Thread/sleep (millis-between-times time-now time)))))

