(ns tesla-solar-charger.recorder.csv-recorder
  (:require
   [tesla-solar-charger.haversine :refer [distance-between-geo-points-meters]]
   [tesla-solar-charger.recorder.recorder :refer [IRecorder]]))

(defn is-car-at-location?
  [location car-state]
  (let [range-meters 50
        distance-between-meters (distance-between-geo-points-meters
                                 (:latitude car-state)
                                 (:longitude car-state)
                                 (:latitude location)
                                 (:longitude location))]
    (< distance-between-meters range-meters)))

(def headers ["Time" "At Location" "Charging" "Excess Power (W)" "Charge Power (W)"])

(defn write-headers 
  [filepath]
  (let [line (str (clojure.string/join "," headers) "\n")]
    (spit filepath line)))

(defn write-row
  [filepath timestamp is-at-location is-charging excess-power charge-power]
  (let [values [timestamp is-at-location is-charging excess-power charge-power]
        line (str (clojure.string/join "," values) "\n")]
    (spit filepath line :append true)))

(defn record-data [filepath location ?car-state ?data-point]
  (when-not (.exists (clojure.java.io/as-file filepath)) (write-headers filepath))
  (let [timestamp (java.time.Instant/now)
        is-at-location (if (nil? ?car-state) false (is-car-at-location? location ?car-state))
        is-charging (get ?car-state :is-charging false)
        excess-power-watts (:excess-power-watts ?data-point)
        charge-power-watts (:charge-power-watts ?car-state)]
    (write-row filepath timestamp is-at-location is-charging excess-power-watts charge-power-watts)))

(defrecord CSVRecorder [filepath]
  IRecorder
  (record-data [recorder location ?car-state ?data-point]
    (try
      (record-data filepath location ?car-state ?data-point)
      {:obj recorder :err nil}
      (catch Exception err
        {:obj recorder :err err})
      (catch clojure.lang.ExceptionInfo err
        {:obj recorder :err err}))))

(defn new-CSVRecorder
  [filepath]
  (->CSVRecorder filepath))
