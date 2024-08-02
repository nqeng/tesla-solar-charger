(ns tesla-solar-charger.recorder.thingspeak-recorder
  (:require
   [tesla-solar-charger.recorder.recorder :as recorder]
   [clj-http.client :as client]))

(defn log-to-thingspeak
  [thingspeak-api-key values]
  (let [url "https://api.thingspeak.com/update"
        query-params (merge {:api_key thingspeak-api-key} values)]
    (client/get url {:query-params query-params})))

(defn record-data
  [car-state data-point thingspeak-api-key]
  (let [excess-power-watts (:excess-power-watts data-point)
        charge-current-amps (:charge-current-amps car-state)
        values {"field1" excess-power-watts
                "field2" charge-current-amps
                "field3" 0
                "field4" 0}]
    (log-to-thingspeak thingspeak-api-key values)))

(defrecord ThingspeakRecorder []
  recorder/IRecorder
  (record-data [recorder car-state data-point] (record-data recorder car-state data-point)))

(defn new-ThingspeakRecorder
  [thingspeak-api-key]
  (let [the-map {:thingspeak-api-key thingspeak-api-key}
        defaults {}]
    (map->ThingspeakRecorder (merge defaults the-map))))

