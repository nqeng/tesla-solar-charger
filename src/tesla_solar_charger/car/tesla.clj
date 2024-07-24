(ns tesla-solar-charger.car.tesla
  (:require
   [clj-http.client :as client]
   [cheshire.core :as json]
   [tesla-solar-charger.utils :as utils]
   [tesla-solar-charger.car.car :as car]))

(def power-to-current-3-phase 687.5)
(def power-to-current-3-phase-delta 262.5)
(def power-to-current-1-phase 231.25)
(def power-to-current-2-phase 462.5)

(defn set-charge-current
  [car new-current-amps]
  (let [vin (:vin car)
        auth-token (:auth-token car)
        url (str "https://api.tessie.com/" vin "/command/set_charging_amps")
        query-params {:retry-duration "40"
                      :wait-for-completion "true"
                      :amps new-current-amps}
        headers {:oauth-token auth-token
                 :accept :json
                 :query-params query-params}]
    (client/get url headers)
    nil))

(defrecord Tesla []
  car/Car
  (get-state [car]
    (let [vin (:vin car)
          auth-token (:auth-token car)
          url (str "https://api.tessie.com/" vin "/state")
          headers {:oauth-token auth-token :accept :json}
          response (client/get url headers)
          json (json/parse-string (:body response))
          drive-state (get json "drive_state")
          charge-state (get json "charge_state")
          vehicle-state (get json "vehicle_state")
          timestamp-millis (max (get drive-state "timestamp") (get charge-state "timestamp") (get vehicle-state "timestamp"))
          timestamp (utils/time-from-epoch-millis timestamp-millis)
          is-connected (not= "Disconnected" (get charge-state "charging_state"))
          is-charging (= "Charging" (get charge-state "charging_state"))
          is-override-active (true? (get vehicle-state "valet_mode"))
          charge-limit-percent (get charge-state "charge_limit_soc")
          minutes-to-full-charge (get charge-state "minutes_to_full_charge")
          charge-current-amps (get charge-state "charge_amps")
          max-charge-current-amps (get charge-state "charge_current_request_max")
          latitude (get drive-state "latitude")
          longitude (get drive-state "longitude")
          state (car/make-car-state timestamp
                                    is-connected
                                    is-charging
                                    is-override-active
                                    charge-limit-percent
                                    minutes-to-full-charge
                                    charge-current-amps
                                    max-charge-current-amps
                                    latitude
                                    longitude)]
      state))
  (get-vin [car] (:vin car))
  (get-name [car] "Tesla")
  (set-charge-current [car new-current-amps]
    (set-charge-current car new-current-amps))
  (restore-this-state [car state-to-restore]
    (let [charge-rate-amps (:charge-current-amps state-to-restore)]
      (car/set-charge-current car charge-rate-amps))))

(defn new-Tesla
  [vin auth-token]
  (let [the-map {:vin vin
                 :auth-token auth-token}
        defaults {}]
    (map->Tesla (merge defaults the-map))))

