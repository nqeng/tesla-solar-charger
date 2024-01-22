(ns tesla-solar-charger.utils
  (:require
   [clj-http.client :as client]
   [tesla-solar-charger.haversine :refer [haversine]]
   [clojure.core.async :as async])
  (:import
   (java.time.temporal ChronoUnit)
   (java.time LocalDateTime)
   (java.time.format DateTimeFormatter)))

(defn power-watts-to-current-amps-single-phase
  [power-watts voltage-volts power-factor]
  (let [power-watts (/ power-watts power-factor)
        current-amps (/ power-watts voltage-volts)]
    current-amps))

(defn power-watts-to-current-amps-three-phase
  [power-watts three-phase-voltage-volts power-factor]
  (let [voltage-per-phase-volts (/ three-phase-voltage-volts (Math/sqrt 3))
        power-per-phase-watts (/ power-watts 3)
        current-per-phase-amps (power-watts-to-current-amps-single-phase power-per-phase-watts voltage-per-phase-volts power-factor)]
    current-per-phase-amps))

(defn euclidean-distance
  [x1 y1 x2 y2]
  (Math/sqrt (+ (Math/pow (- x2 x1) 2) (Math/pow (- y2 y1) 2))))

(def distance-between-geo-points-kilometers haversine)

(defn distance-between-geo-points-meters
  [{lng1 :lng lat1 :lat} {lng2 :lng lat2 :lat}]
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

(def output-channel-closed (ex-info "Output channel unexpectedly closed" {:type :output-channel-closed}))

(defn throw-output-channel-closed
  []
  (throw output-channel-closed))

(defn throw-if-attribute-nil
  [map key]
  (when (nil? (get map key))
    (throw (ex-info (format "Attribute %s of map %s was nil" key map) {:type :attribute-nil}))))

(defn time-from-epoch-millis
  [millis]
  (java.time.LocalDateTime/ofEpochSecond (long (/ millis 1000)) 0 java.time.ZoneOffset/UTC))

(defn parse-time
  [time-str format-str]
  (java.time.LocalDateTime/parse time-str (java.time.format.DateTimeFormatter/ofPattern format-str)))

(defn calc-minutes-between-times
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
   (.format time (DateTimeFormatter/ofPattern format-str)))
  ([time]
   (format-time "yyyy-MM-dd HH:mm:ss" time)))

(defn time-now
  []
  (LocalDateTime/now))

(defn time-after-seconds
  [seconds]
  (.plusSeconds (time-now) seconds))

(defn sleep-until
  [time]
  (let [time-now (time-now)]
    (when (.isAfter time time-now)
      (Thread/sleep (millis-between-times time-now time)))))

