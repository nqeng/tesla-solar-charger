(ns tesla-solar-charger.car-charge-setter.tessie-charge-setter
  (:require
   [tesla-solar-charger.car-charge-setter.car-charge-setter :refer [ICarChargeSetter]]
   [clojure.math :refer [sqrt]]
   [clj-http.client :as client]))

(defn watts-to-amps-three-phase
  [power-watts voltage-volts power-factor]
  (/ power-watts (* voltage-volts power-factor (sqrt 3))))

(defn watts-to-amps-three-phase-australia
  [power-watts]
  (let [voltage-volts 400
        power-factor 1.0]
    (watts-to-amps-three-phase power-watts voltage-volts power-factor)))

(defn set-charge-current-tessie
  [tessie-auth-token vehicle-vin charge-current-amps]
  (let [url (str "https://api.tessie.com/" vehicle-vin "/command/set_charging_amps")
        query-params {:retry-duration "40"
                      :wait-for-completion "true"
                      :amps (str (int charge-current-amps))}
        _ (client/get url {:oauth-token tessie-auth-token
                           :accept :json
                           :query-params query-params})]
    nil))

(defn set-charge-power-tessie
  [tessie-auth-token vehicle-vin charge-power-watts]
  (let [charge-current-amps (watts-to-amps-three-phase-australia charge-power-watts)]
    (set-charge-current-tessie tessie-auth-token vehicle-vin charge-current-amps)))

(defrecord TessieChargeSetter []
  ICarChargeSetter
  (set-charge-power [data-source charge-power-watts]
    (let [vehicle-vin (:vehicle-vin data-source)
          tessie-auth-token (:tessie-auth-token data-source)]
      (set-charge-power-tessie tessie-auth-token vehicle-vin charge-power-watts))))

(defn new-TessieChargeSetter
  [tessie-auth-token vehicle-vin]
  (let [the-map {:vehicle-vin vehicle-vin
                 :tessie-auth-token tessie-auth-token}
        defaults {}]
    (map->TessieChargeSetter (merge defaults the-map))))
