(ns tesla-solar-charger.env
  (:require
    [dotenv :refer [env]])
  )

(defn get-env
  [var-name]
  (let [value (env var-name)]
    (if (nil? value)
      (throw (ex-info (format "Environment variable \"%s\" not found" var-name) {:type :environment-variable-not-found}))
      value))
  )

(def sungrow-username (get-env "SUNGROW_USERNAME"))
(def sungrow-password (get-env "SUNGROW_PASSWORD"))
(def tessie-token (get-env "TESSIE_TOKEN"))
(def tesla-vin (get-env "TESLA_VIN"))
(def power-buffer-watts (Float/parseFloat (get-env "POWER_BUFFER_WATTS")))
(def max-climb-amps (Float/parseFloat (get-env "MAX_CLIMB_AMPS")))
(def max-drop-amps (Float/parseFloat (get-env "MAX_DROP_AMPS")))
(def charger-latitude (Float/parseFloat (get-env "CHARGER_LATITUDE")))
(def charger-longitude (Float/parseFloat (get-env "CHARGER_LONGITUDE")))
(def grid-sensor-device-id (get-env "GRID_SENSOR_DEVICE_ID"))
(def grid-power-data-id (get-env "GRID_POWER_DATA_ID"))
