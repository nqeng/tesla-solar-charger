(ns tesla-solar-charger.implementations.car.dummy-tesla
  (:require
   [tesla-solar-charger.interfaces.car :as Icar]
   [tesla-solar-charger.interfaces.site :as Isite]
   [tesla-solar-charger.utils :as utils]
   [cheshire.core :as json]
   [tesla-solar-charger.interfaces.site-charger :as Isite-charger]))







(def default-state
  Icar)

(defn read-car-state-from-file
  [car]
  (let [filename (-> car
                     Icar/get-vin
                     (str ".json"))
        contents (slurp filename)
        json (json/parse-string contents)
        state (new-DummyTeslaState json)]
    state))

(defn write-car-state-to-file
  [car car-state]
  (let [filename (-> car
                     Icar/get-vin
                     (str ".json"))
        json (json/generate-string car-state {:pretty true})]
    (spit filename json)))

(defrecord DummyTesla []

  Icar/Car

  (get-state [car]
    (let [new-state (try
                      (read-car-state-from-file car)
                      (catch Exception e default-state))
          epoch-seconds-now (* 1000 (.getEpochSecond (java.time.Instant/now)))
          new-state (assoc-in new-state [:object "charge_rate" "timestamp"] epoch-seconds-now)]
      [car new-state]))

  (get-vin [car] (utils/throw-if-attribute-nil car :vin) (:vin car))
  (get-name [car] "Dummy")
  (set-charge-current [car current-amps]
    (let [car-state (Icar/get-state car)
          max-charge-current-amps (Icar/get-max-charge-current-amps car-state)
          new-charge-current-amps (utils/clamp-min-max current-amps 0 max-charge-current-amps)
          new-state (assoc-in car-state [:object "charge_state" "charge_amps"] new-charge-current-amps)]
      (write-car-state-to-file car new-state)))
  (restore-this-state [car state-to-restore]
    (let [charge-rate-amps (Icar/get-charge-current-amps state-to-restore)]
      (Icar/set-charge-current car charge-rate-amps))))

(defn new-DummyTesla
  [vin]
  (let [the-map {:vin vin}
        defaults {}
        car (map->DummyTesla (merge defaults the-map))]
    car))

