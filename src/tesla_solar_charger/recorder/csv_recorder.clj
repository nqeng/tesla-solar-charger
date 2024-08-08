(ns tesla-solar-charger.recorder.csv-recorder
  (:require
   [tesla-solar-charger.recorder.recorder :refer [IRecorder]]))

(def headers ["Time" "Excess Power (W)" "Charge Power (W)"])

(defn write-headers 
  [filepath]
  (spit filepath (str (clojure.string/join "," headers) "\n")))

(defn write-row
  [filepath timestamp excess-power charge-power]
  (spit filepath (str (clojure.string/join "," headers) "\n")))

(defn record-data [filepath ?car-state ?data-point]
  (when-not (.exists (clojure.java.io/as-file filepath)) (write-headers filepath))
  (let [timestamp (java.time.Instant/now)
        excess-power-watts (:excess-power-watts ?data-point)
        charge-power-watts (:charge-power-watts ?car-state)]
    (write-row filepath timestamp excess-power-watts charge-power-watts)))

(defrecord CSVRecorder [filepath]
  IRecorder
  (record-data [recorder ?car-state ?data-point] 
    (try
      (record-data filepath ?car-state ?data-point)
      {:obj recorder :err nil}
      (catch Exception err
        {:obj recorder :err err})
      (catch clojure.lang.ExceptionInfo err
        {:obj recorder :err err}))))

(defn new-CSVRecorder
  [filepath]
  (->CSVRecorder filepath))
