(ns tesla-solar-charger.recorder.thingspeak-recorder
  (:require
   [tesla-solar-charger.recorder.recorder :refer [IRecorder]]
   [clj-http.client :as client]))

(defn send-to-thingspeak
  [api-key values]
  (let [url "https://api.thingspeak.com/update"
        query-params (merge {:api_key api-key} values)]
    (client/get url {:query-params query-params})))

(defn record-data-thingspeak
  [?car-state ?data-point api-key]
  (let [excess-power-watts (:excess-power-watts ?data-point)
        charge-current-amps (:charge-current-amps ?car-state)
        values {"field1" excess-power-watts
                "field2" charge-current-amps
                "field3" 0
                "field4" 0}]
    (send-to-thingspeak api-key values)))

(defrecord ThingspeakRecorder [api-key]
  IRecorder
  (record-data [recorder ?car-state ?data-point] 
    (try
      (record-data-thingspeak ?car-state ?data-point api-key)
      {:obj recorder :err nil}
      (catch Exception err
        {:obj recorder :err err})
      (catch clojure.lang.ExceptionInfo err
        {:obj recorder :err err}))))

(defn new-ThingspeakRecorder
  [api-key]
  (-> ThingspeakRecorder api-key))

