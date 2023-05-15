(ns sungrow-tesla.tesla
  (:require
   [clj-http.client :as client]
   [cheshire.core :as json]
   [dotenv :refer [env]]))

(defn request-vehicle-state
  "Sends a request to Tessie to retrieve the state of a Tesla vehicle.
  Overloaded to use environment variables."
  ([tesla-vin tessie-access-token]
   (try
     (let [response (client/get
                     (str "https://api.tessie.com/" tesla-vin "/state")
                     {:oauth-token tessie-access-token
                      :accept :json})
           json (json/parse-string (:body response))]
       json)
     (catch clojure.lang.ExceptionInfo e
       (let [error (-> (ex-data e)
                       (:body)
                       (json/parse-string)
                       (get "error"))]
         (throw (ex-info
                 (str "Failed to get Tesla state; " error)
                 {:type :err-could-not-get-tesla-state}))))))
  ([]
   (request-vehicle-state (env "TESLA_VIN") (env "TESSIE_TOKEN"))))

(defn update-charge-amps
  "Sends a request to Tessie to set the charge speed of a Tesla vehicle.
  Overloaded to use environment variables."
  ([tesla-vin tessie-token charge-speed-amps]
   (try
     (let [response (client/get
                     (str "https://api.tessie.com/" tesla-vin "/command/set_charging_amps")
                     {:oauth-token tessie-token
                      :query-params {:retry-duration "40"
                                     :wait-for-completion "true"
                                     :amps (str charge-speed-amps)}
                      :accept :json})
           json (json/parse-string (:body response))]
       json)
     (catch clojure.lang.ExceptionInfo e
       (let [error (-> (ex-data e)
                       (:body)
                       (json/parse-string)
                       (get "error"))]
         (throw (ex-info
                 (str "Failed to set Tesla charge amps; " error)
                 {:type :err-could-not-set-charge-amps}))))))
  ([charge-speed-amps]
   (update-charge-amps (env "TESLA_VIN") (env "TESSIE_TOKEN") charge-speed-amps)))

(defn get-max-amps
  [tesla-state]
  (get-in tesla-state ["charge_state" "charge_current_request_max"]))

(defn get-charge-amps
  [tesla-state]
  (get-in tesla-state ["charge_state" "charge_amps"]))

(defn get-tesla-charge-state
  [tesla-state]
  (get-in tesla-state ["charge_state" "charging_state"]))

(defn get-tesla-longitude
  [tesla-state]
  (get-in tesla-state ["drive_state" "longitude"]))

(defn get-tesla-latitude
  [tesla-state]
  (get-in tesla-state ["drive_state" "latitude"]))

(defn is-tesla-charging?
  [tesla-state]
  (let [tesla-charge-state (get-tesla-charge-state tesla-state)]
    (= "Charging" tesla-charge-state)))

(defn get-battery-level
  [tesla-state]
  (get-in tesla-state ["charge_state" "battery_level"]))

(defn is-tesla-in-valet-mode?
  [tesla-state]
  (get-in tesla-state ["vehicle_state" "valet_mode"]))

(defn is-location-within-geofence?
  [latitude longitude geofence]
  (and
   (< latitude  (:north geofence))
   (> latitude  (:south geofence))
   (< longitude (:east geofence))
   (> longitude (:west geofence))))

(defn is-tesla-near-charger?
  "Returns true if Tesla is within charger's geofence, otherwise false.
  Overloaded to use environment variables."
  ([tesla-state charger-geofence]
   (let [latitude (get-tesla-latitude tesla-state)
         longitude (get-tesla-longitude tesla-state)]
     (is-location-within-geofence? latitude longitude charger-geofence)))
  ([tesla-state]
   (is-tesla-near-charger? tesla-state
                           {:north (Float/parseFloat (env "CHARGER_GEOFENCE_NORTH"))
                            :south (Float/parseFloat (env "CHARGER_GEOFENCE_SOUTH"))
                            :east (Float/parseFloat (env "CHARGER_GEOFENCE_EAST"))
                            :west (Float/parseFloat (env "CHARGER_GEOFENCE_WEST"))})))

