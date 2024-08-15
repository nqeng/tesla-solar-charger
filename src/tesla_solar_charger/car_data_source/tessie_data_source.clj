(ns tesla-solar-charger.car-data-source.tessie-data-source
  (:require
   [clj-http.client :as client]
   [cheshire.core :as json]
   [tesla-solar-charger.utils :as utils]
   [tesla-solar-charger.car-data-source.car-data-source :refer [ICarDataSource make-car-state]]))

(def power-to-current-3-phase 687.5)
(def power-to-current-3-phase-delta 262.5)
(def power-to-current-1-phase 231.25)
(def power-to-current-2-phase 462.5)

(defn amps-to-watts-three-phase
  [current-amps voltage-volts power-factor]
  (* voltage-volts current-amps power-factor (clojure.math/sqrt 3)))

(defn amps-to-watts-three-phase-australia
  [current-amps]
  (let [voltage-volts 400
        power-factor 1.0]
    (amps-to-watts-three-phase current-amps voltage-volts power-factor)))

(defn get-locationiq-reverse-geocode
  [locationiq-auth-token latitude longitude]
  (let [url "https://us1.locationiq.com/v1/reverse"
        query-params {:lat (str latitude)
                      :lon (str longitude)
                      :format "json"
                      :key locationiq-auth-token}
        response (client/get url {:query-params query-params 
                                  :accept :json})
        json-object (json/parse-string (:body response))]
    json-object))

(defn get-readable-location-name
  [latitude longitude locationiq-auth-token]
  (let [json-object (get-locationiq-reverse-geocode
                      locationiq-auth-token
                      latitude
                      longitude)
        address (get json-object "address")
        house-number (get address "house_number")
        road (get address "road")
        city (get address "city")
        readable-name (format "%s%s, %s"
                              (if (some? house-number) (format "%s " house-number) "")
                              road
                              city)]
    readable-name))

(defn get-latest-car-state
  [vehicle-vin tessie-auth-token locationiq-auth-token]
  (let [url (str "https://api.tessie.com/" vehicle-vin "/state")
        response (client/get url {:oauth-token tessie-auth-token :accept :json})
        json (json/parse-string (:body response))
        drive-state (get json "drive_state")
        charge-state (get json "charge_state")
        vehicle-state (get json "vehicle_state")
        timestamp-millis (max (get drive-state "timestamp") (get charge-state "timestamp") (get vehicle-state "timestamp"))
        timestamp (java.time.Instant/ofEpochMilli timestamp-millis)
        is-connected (not= "Disconnected" (get charge-state "charging_state"))
        is-charging (= "Charging" (get charge-state "charging_state"))
        is-override-active (true? (get vehicle-state "valet_mode"))
        charge-limit-percent (get charge-state "charge_limit_soc")
        battery-percent (get charge-state "battery_level")
        minutes-to-full-charge (get charge-state "minutes_to_full_charge")
        charge-current-amps (get charge-state "charge_amps")
        charge-power-watts (amps-to-watts-three-phase-australia charge-current-amps)
        max-charge-current-amps (get charge-state "charge_current_request_max")
        max-charge-power-watts (amps-to-watts-three-phase-australia max-charge-current-amps)
        latitude (get drive-state "latitude")
        longitude (get drive-state "longitude")
        readable-location-name (get-readable-location-name latitude longitude locationiq-auth-token)
        state (make-car-state timestamp
                              is-connected
                              is-charging
                              is-override-active
                              charge-limit-percent
                              minutes-to-full-charge
                              charge-power-watts
                              max-charge-power-watts
                              battery-percent
                              latitude
                              longitude
                              readable-location-name)]
    (when (nil? state)
      (throw (ex-info "No tessie car state" {})))
    state))

(defrecord TessieDataSource []
  ICarDataSource
  (get-latest-car-state [data-source]
    (try
      (let [vehicle-vin (:vehicle-vin data-source)
          tessie-auth-token (:tessie-auth-token data-source)
          locationiq-auth-token (:locationiq-auth-token data-source)
          car-state (get-latest-car-state vehicle-vin tessie-auth-token locationiq-auth-token)]
      {:obj data-source :val car-state :err nil})
      (catch clojure.lang.ExceptionInfo err
        {:obj data-source :val nil :err err})
      (catch Exception err
        {:obj data-source :val nil :err err}))))

(defn new-TessieDataSource
  [vehicle-vin tessie-auth-token locationiq-auth-token]
  (let [the-map {:vehicle-vin vehicle-vin
                 :tessie-auth-token tessie-auth-token
                 :locationiq-auth-token locationiq-auth-token}
        defaults {}]
    (map->TessieDataSource (merge defaults the-map))))

