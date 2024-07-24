(ns tesla-solar-charger.implementations.tesla
  (:require
   [clj-http.client :as client]
   [cheshire.core :as json]
   [tesla-solar-charger.utils :as utils]
   [tesla-solar-charger.interfaces.car :as car]))

(def power-to-current-3-phase 687.5)
(def power-to-current-3-phase-delta 262.5)
(def power-to-current-1-phase 231.25)
(def power-to-current-2-phase 462.5)

(defrecord TeslaState [object]

  car/CarState
  (get-time [state] (utils/local-time-from-epoch-millis (get-in object ["charge_state" "timestamp"])))

  (get-id [state]
    (str
     (get-in object ["charge_state" "timestamp"])
     (get-in object ["drive_state" "timestamp"])))

  #_(is-charging? [state] (= "Charging" (get-in object ["charge_state" "charging_state"])))
  (is-charging? [state] true)

  (is-override-active? [state] (true? (get-in object ["vehicle_state" "valet_mode"])))

  (get-minutes-to-target-percent [state target-percent]
    (let [charge-limit-percent (car/get-charge-limit-percent state)
          minutes-to-full-charge (car/get-minutes-to-full-charge state)
          minutes-per-percent (/ minutes-to-full-charge charge-limit-percent)
          minutes-to-target-percent (* minutes-per-percent target-percent)]
      minutes-to-target-percent))

  (get-minutes-to-target-percent-at-max-rate [state target-percent]
    (let [charge-rate-amps (car/get-charge-rate-amps state)
          max-charge-rate-amps (car/get-max-charge-rate-amps state)
          minutes-to-target-percent (car/get-minutes-to-target-percent state target-percent)
          minutes-per-amp (/ minutes-to-target-percent max-charge-rate-amps)
          minutes-to-target-percent-at-max-rate (* minutes-per-amp charge-rate-amps)]
      minutes-to-target-percent-at-max-rate))

  (will-reach-target-by? [state target-percent target-time]
    (< (car/get-minutes-to-target-percent state target-percent)
       (utils/minutes-between-times (car/get-time state) target-time)))

  (should-override-to-reach-target?
    [state target-percent target-time]
    (let [minutes-to-target-percent-at-max-rate (car/get-minutes-to-target-percent-at-max-rate state target-percent)
          minutes-left-to-charge (utils/minutes-between-times (car/get-time state) target-time)]
      (< minutes-left-to-charge minutes-to-target-percent-at-max-rate)))

  (get-charge-rate-amps [state] (get-in object ["charge_state" "charge_amps"]))

  (get-charge-limit-percent [state] (get-in object ["charge_state" "charge_limit_soc"]))

  (get-battery-level-percent [state] (get-in object ["charge_state" "battery_level"]))

  (get-charger-power-kilowatts [state] (get-in object ["charge_state" "charger_power"]))

  (get-max-charge-rate-amps [state] (get-in object ["charge_state" "charge_current_request_max"]))

  (get-minutes-to-full-charge [state] (get-in object ["charge_state" "minutes_to_full_charge"]))

  (get-latitude [state] (get-in object ["drive_state" "latitude"]))

  (get-longitude [state] (get-in object ["drive_state" "longitude"])))

(defrecord Tesla [vin auth-token]
  car/Car

  (get-state [car]
    (try
      (->TeslaState
       (let [response
             (try
               (client/get
                (str "https://api.tessie.com/" vin "/state")
                {:oauth-token auth-token
                 :accept :json})

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
      (catch java.net.UnknownHostException e
        (let [error (.getMessage e)]
          (throw (ex-info
                  (str "Network error; " error)
                  {:type :network-error}))))
      (catch java.net.NoRouteToHostException e
        (let [error (.getMessage e)]
          (throw (ex-info
                  (str "Network error; " error)
                  {:type :network-error}))))))

  (get-vin [car] vin)

  (get-name [car] "Tesla")

  (set-charge-rate [car new-charge-rate-amps]
    (try
      (let [response (client/get
                      (str "https://api.tessie.com/" vin "/command/set_charging_amps")
                      {:oauth-token auth-token
                       :query-params {:retry-duration "40"
                                      :wait-for-completion "true"
                                      :amps (str new-charge-rate-amps)}
                       :accept :json})
            json (json/parse-string (:body response))]
        json)
      (catch java.net.UnknownHostException e
        (let [error (.getMessage e)]
          (throw (ex-info
                  (str "Network error; " error)
                  {:type :network-error}))))
      (catch java.net.NoRouteToHostException e
        (let [error (.getMessage e)]
          (throw (ex-info
                  (str "Network error; " error)
                  {:type :network-error}))))
      (catch clojure.lang.ExceptionInfo e
        (let [error (-> (ex-data e)
                        (:body)
                        (json/parse-string)
                        (get "error"))]
          (throw (ex-info
                  (str "Failed to set Tesla charge amps; " error)
                  {:type :err-could-not-set-charge-amps}))))))

  (restore-state [car state]
    (let [charge-rate-amps (car/get-charge-rate-amps state)
          charge-limit-percent (car/get-charge-limit-percent state)]
      (car/set-charge-rate car charge-rate-amps))))
