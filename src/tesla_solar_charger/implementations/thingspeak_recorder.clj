(ns tesla-solar-charger.implementations.thingspeak-recorder
  (:require
   [tesla-solar-charger.interfaces.recorder :as recorder]
   [tesla-solar-charger.interfaces.site :as site]
   [tesla-solar-charger.interfaces.car :as car]))

#_(defn log-to-thingspeak
  [& data]
  (let [field-names (take-nth 2 data)
        field-values (take-nth 2 (rest data))
        url "https://api.thingspeak.com/update"
        query-params (into {:api_key env/thingspeak-api-key}
                           (map (fn [key value] {key value}) field-names field-values))]
    (try
      (client/get url {:query-params query-params})
      (catch java.net.UnknownHostException e
        (let [error (.getMessage e)]
          (throw (ex-info
                  (str "Network error; " error)
                  {:type :network-error}))))
      (catch java.net.NoRouteToHostException e
        (let [error (.getMessage e)]
          (throw (ex-info
                  (str "Failed to get Tesla state; " error)
                  {:type :network-error})))))))

#_(defrecord ThingspeakRecorder [api-key]

  recorder/Recorder

  (record-data [recorder car-state site-data]
    (if (and
         (some? (:next-record-time recorder))
         (.isBefore (java.time.Instant/now) (:next-record-time recorder)))
      recorder
      (let [field1 (site/get-excess-power-watts site-data)
            field2 (car/get-charge-current-amps car-state)
            field3 0
            field4 0]
        (try
          (log-to-thingspeak "field1" field1 "field2" field2 "field3" field3 "field4" field4)
          (-> recorder
              (assoc :next-record-time (.plusSeconds (java.time.Instant/now) 30)))
          (catch clojure.lang.ExceptionInfo e
            (throw e))
          (catch Exception e
            (throw e)))))))

