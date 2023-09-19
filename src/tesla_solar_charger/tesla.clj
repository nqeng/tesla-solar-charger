(ns tesla-solar-charger.tesla
  (:require
   [tesla-solar-charger.env :as env]
   [clj-http.client :as client]
   [cheshire.core :as json]))

(def power-to-current-3-phase 687.5)
(def power-to-current-3-phase-delta 262.5)
(def power-to-current-1-phase 231.25)
(def power-to-current-2-phase 462.5)

(defn get-data
  ([tesla-vin tessie-token]
   (let [response
         (try
           (client/get
            (str "https://api.tessie.com/" tesla-vin "/state")
            {:oauth-token tessie-token
             :accept :json})

           (catch java.net.UnknownHostException e
             (let [error (.getMessage e)]
               (throw (ex-info
                       (str "Failed to get Tesla state; " error)
                       {:type :network-error}))))
           (catch java.net.NoRouteToHostException e
             (let [error (.getMessage e)]
               (throw (ex-info
                       (str "Failed to get Tesla state; " error)
                       {:type :network-error}))))
           (catch clojure.lang.ExceptionInfo e
             (let [error (-> (ex-data e)
                             (:body)
                             (json/parse-string)
                             (get "error"))]
               (throw (ex-info
                       (str "Failed to get Tesla state; " error)
                       {:type :err-could-not-get-tesla-state})))))
         json (json/parse-string (:body response))]
     json))
  ([]
   (get-data env/tesla-vin env/tessie-token)))

(defn set-charge-rate
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
   (set-charge-rate env/tesla-vin env/tessie-token charge-speed-amps)))

(defn set-charge-limit
  ([tesla-vin tessie-token charge-limit-percent]
   (try
     (let [response (client/get
                     (str "https://api.tessie.com/" tesla-vin "/command/set_charge_limit")
                     {:oauth-token tessie-token
                      :query-params {:retry-duration "40"
                                     :wait-for-completion "true"
                                     :percent (str charge-limit-percent)}
                      :accept :json})
           json (json/parse-string (:body response))]
       json)
     (catch clojure.lang.ExceptionInfo e
       (let [error (-> (ex-data e)
                       (:body)
                       (json/parse-string)
                       (get "error"))]
         (throw (ex-info
                 (str "Failed to set Tesla charge limit; " error)
                 {:type :err-could-not-set-charge-limit}))))))
  ([charge-limit-percent]
   (set-charge-limit env/tesla-vin env/tessie-token charge-limit-percent)))

(defn get-minutes-to-full-charge
  [tesla-state]
  (get-in tesla-state ["charge_state" "minutes_to_full_charge"]))

(defn get-max-charge-rate
  [tesla-state]
  (get-in tesla-state ["charge_state" "charge_current_request_max"]))

(defn get-charge-rate
  [tesla-state]
  (get-in tesla-state ["charge_state" "charge_amps"]))

(defn get-charge-limit
  [tesla-state]
  (get-in tesla-state ["charge_state" "charge_limit_soc"]))

(defn get-tesla-charge-state
  [tesla-state]
  (get-in tesla-state ["charge_state" "charging_state"]))

(defn get-tesla-longitude
  [tesla-state]
  (get-in tesla-state ["drive_state" "longitude"]))

(defn get-tesla-latitude
  [tesla-state]
  (get-in tesla-state ["drive_state" "latitude"]))

(defn get-minutes-to-full-charge-at-max-rate
  [tesla-state]
  (-> (get-charge-rate tesla-state)
      (/ (get-max-charge-rate tesla-state))
      (* (get-minutes-to-full-charge tesla-state))
      (int)))

(defn is-charging?
  [tesla-state]
  (let [tesla-charge-state (get-tesla-charge-state tesla-state)]
    (= "Charging" tesla-charge-state)))

(defn is-charging-complete?
  [tesla-state]
  (let [tesla-charge-state (get-tesla-charge-state tesla-state)]
    (= "Complete" tesla-charge-state)))

(defn get-battery-level-percent
  [tesla-state]
  (get-in tesla-state ["charge_state" "battery_level"]))

(defn get-current-playing-song
  [tesla-state]
  (get-in tesla-state ["vehicle_state" "media_info" "now_playing_title"]))

(defn is-playing-walking-on-sunshine?
  [tesla-state]
  (= "Walking on Sunshine" (get-current-playing-song tesla-state)))

(defn is-in-valet-mode?
  [tesla-state]
  (get-in tesla-state ["vehicle_state" "valet_mode"]))

(defn is-override-active?
  [tesla-state]
  (is-in-valet-mode? tesla-state))

(defn euclidean-distance
  [x1 y1 x2 y2]
  (Math/sqrt (+ (Math/pow (- x2 x1) 2) (Math/pow (- y2 y1) 2))))

(defn is-near-charger?
  ([tesla-state charger-latitude charger-longitude]
   (let [tesla-latitude (get-tesla-latitude tesla-state)
         tesla-longitude (get-tesla-longitude tesla-state)
         distance-between (euclidean-distance
                           charger-latitude
                           charger-longitude
                           tesla-latitude
                           tesla-longitude)]
     (< distance-between 0.0005)))
  ([tesla-state]
   (is-near-charger? tesla-state
                     env/charger-latitude
                     env/charger-longitude)))

(defn is-near-location?
  [tesla-state latitude longitude]
  (< (euclidean-distance
      latitude
      longitude
      (get-tesla-latitude tesla-state)
      (get-tesla-longitude tesla-state)) 0.0005))
