(ns tesla-solar-charger.time-utils
  (:require
    [clj-http.client :as client]
   [clojure.core.async :as async])
  (:import
   (java.time.temporal ChronoUnit)
   (java.time LocalDateTime)
   (java.time.format DateTimeFormatter)))

(defn send-to-ntfy
  [message]
  (try (client/post "https://ntfy.sh/github-nqeng-tesla-solar-charger" {:body message})))

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
  [format-str time]
  (.format time (DateTimeFormatter/ofPattern format-str)))

(defn time-now
  []
  (LocalDateTime/now))

(defn time-after-seconds
  [seconds]
  (.plusSeconds (time-now) seconds))
