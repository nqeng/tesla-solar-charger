(ns tesla-solar-charger.implementations.car.tesla
  (:require
   [clj-http.client :as client]
   [cheshire.core :as json]
   [tesla-solar-charger.interfaces.site :as Isite]
   [tesla-solar-charger.utils :as utils]
   [tesla-solar-charger.interfaces.car :as Icar]))

(def power-to-current-3-phase 687.5)
(def power-to-current-3-phase-delta 262.5)
(def power-to-current-1-phase 231.25)
(def power-to-current-2-phase 462.5)

(defn get-charge-status
  [state]
  (get-in (:object state) ["charge_state" "charging_state"]))

(defrecord TeslaState []

  Icar/CarState

  (get-time [state] (utils/time-from-epoch-millis (get-in (:object state) ["charge_state" "timestamp"])))
  (get-id [state]
    (str
     (get-in (:object state) ["charge_state" "timestamp"])
     (get-in (:object state) ["drive_state" "timestamp"])))
  (is-newer? [state other-state]
    (cond
      (nil? state) false
      (nil? other-state) true
      :else (.isAfter (Icar/get-time state) (Icar/get-time other-state))))
  (is-charging? [state] (= "Charging" (get-charge-status state)))
  (is-connected? [state] (not= "Disconnected" (get-charge-status state)))
  (is-override-active? [state] (true? (get-in (:object state) ["vehicle_state" "valet_mode"])))
  (get-minutes-to-target-percent [state target-percent]
    (let [charge-limit-percent (Icar/get-charge-limit-percent state)
          minutes-to-full-charge (Icar/get-minutes-to-full-charge state)
          minutes-per-percent (/ minutes-to-full-charge charge-limit-percent)
          minutes-to-target-percent (* minutes-per-percent target-percent)]
      minutes-to-target-percent))
  (get-minutes-to-target-percent-at-max-rate [state target-percent]
    (let [charge-rate-amps (Icar/get-charge-current-amps state)
          max-charge-rate-amps (Icar/get-max-charge-current-amps state)
          minutes-to-target-percent (Icar/get-minutes-to-target-percent state target-percent)
          minutes-per-amp (/ minutes-to-target-percent max-charge-rate-amps)
          minutes-to-target-percent-at-max-rate (* minutes-per-amp charge-rate-amps)]
      minutes-to-target-percent-at-max-rate))
  (will-reach-target-by? [state target-percent target-time]
    (< (Icar/get-minutes-to-target-percent state target-percent)
       (utils/calc-minutes-between-times (Icar/get-time state) target-time)))
  (should-override-to-reach-target?
    [state target-percent target-time]
    (let [minutes-to-target-percent-at-max-rate (Icar/get-minutes-to-target-percent-at-max-rate state target-percent)
          minutes-left-to-charge (utils/calc-minutes-between-times (Icar/get-time state) target-time)]
      (< minutes-left-to-charge minutes-to-target-percent-at-max-rate)))
  (get-charge-current-amps [state] (get-in (:object state) ["charge_state" "charge_amps"]))
  (get-charge-limit-percent [state] (get-in (:object state) ["charge_state" "charge_limit_soc"]))
  (get-max-charge-current-amps [state] (get-in (:object state) ["charge_state" "charge_current_request_max"]))
  (get-minutes-to-full-charge [state] (get-in (:object state) ["charge_state" "minutes_to_full_charge"]))
  (get-latitude [state] (get-in (:object state) ["drive_state" "latitude"]))
  (get-longitude [state] (get-in (:object state) ["drive_state" "longitude"]))

  utils/Printable

  (string-this [this] (format "Tesla is at (%s, %s) (%s) as of %s"
                              (Icar/get-latitude this)
                              (Icar/get-longitude this)
                              (if (Icar/is-charging? this) "charging" "not charging")
                              (utils/format-time (Icar/get-time this))))
  (print-this [this] (println (utils/string-this this))))

(defn new-TeslaState
  [object]
  (let [the-map {:object object}
        defaults {}
        state (map->TeslaState (merge defaults the-map))]
    state))

(defrecord Tesla []
  Icar/Car

  (get-state [car]
    (let [vin (:vin car)
          auth-token (:auth-token car)]
      (try
        (let [response (try
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
              json (json/parse-string (:body response))
              state (new-TeslaState json)]
          [car state])

        (catch java.net.UnknownHostException e
          (let [error (.getMessage e)]
            (throw (ex-info
                    (str "Network error; " error)
                    {:type :network-error}))))
        (catch java.net.NoRouteToHostException e
          (let [error (.getMessage e)]
            (throw (ex-info
                    (str "Network error; " error)
                    {:type :network-error})))))))

  (get-vin [car] (:vin car))
  (get-name [car] "Tesla")
  (set-charge-current [car new-charge-current-amps]
    (let [vin (:vin car)
          auth-token (:auth-token car)]
      (try
        (let [response (client/get
                        (str "https://api.tessie.com/" vin "/command/set_charging_amps")
                        {:oauth-token auth-token
                         :query-params {:retry-duration "40"
                                        :wait-for-completion "true"
                                        :amps (str new-charge-current-amps)}
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
                    {:type :err-could-not-set-charge-amps})))))))
  (restore-this-state [car state-to-restore]
    (let [charge-rate-amps (Icar/get-charge-current-amps state-to-restore)]
      (Icar/set-charge-current car charge-rate-amps))))

(defn new-Tesla
  [vin auth-token]
  (let [the-map {:vin vin
                 :auth-token auth-token}
        defaults {}]
    (map->Tesla (merge defaults the-map))))
