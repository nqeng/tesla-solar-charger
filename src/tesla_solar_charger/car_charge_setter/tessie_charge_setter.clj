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
  (let [url (format "https://api.tessie.com/%s/command/set_charging_amps" vehicle-vin)
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

(defrecord TessieChargeSetter 
  [tessie-auth-token vehicle-vin]
  ICarChargeSetter
  (set-charge-power [charge-setter charge-power-watts]
    (try
        (set-charge-power-tessie tessie-auth-token vehicle-vin charge-power-watts)
        {:obj charge-setter :err nil}
        (catch clojure.lang.ExceptionInfo err
          {:obj charge-setter :err err})
        (catch Exception err
          {:obj charge-setter :err err}))))

(defn new-TessieChargeSetter
  [tessie-auth-token vehicle-vin]
  (->TessieChargeSetter tessie-auth-token vehicle-vin))

