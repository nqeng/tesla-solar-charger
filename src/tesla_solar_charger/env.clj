(ns tesla-solar-charger.env
  (:require
    [dotenv :refer [env]])
  )

(defn get-env
  [var-name]
  (let [value (env var-name)]
    (if (not (nil? value))
      value
      (throw (ex-info 
               (format "Environment variable \"%s\" not found" var-name) 
               {:type :environment-variable-not-found})))))

(defn get-float
  [var-name]
  (try
    (Float/parseFloat (get-env var-name))
    (catch java.lang.NumberFormatException e
      (throw (ex-info 
               (format "Environment variable \"%s\" is not a valid number" var-name) 
               {:type :environment-variable-not-found})))))

(defn get-int
  [var-name]
  (try
    (Integer/parseInt (get-env var-name))
    (catch java.lang.NumberFormatException e
      (throw (ex-info 
               (format "Environment variable \"%s\" is not a valid number" var-name) 
               {:type :environment-variable-not-found})))))

(def sungrow-username (get-env "SUNGROW_USERNAME"))
(def sungrow-password (get-env "SUNGROW_PASSWORD"))
(def tessie-token (get-env "TESSIE_TOKEN"))
(def tesla-vin (get-env "TESLA_VIN"))
(def power-buffer-watts (get-float "POWER_BUFFER_WATTS"))
(def max-climb-amps (get-int "MAX_CLIMB_AMPS"))
(def max-drop-amps (get-int "MAX_DROP_AMPS"))
(def charger-latitude (get-float "CHARGER_LATITUDE"))
(def charger-longitude (get-float "CHARGER_LONGITUDE"))
(def grid-sensor-device-id (get-env "GRID_SENSOR_DEVICE_ID"))
(def grid-power-data-id (get-env "GRID_POWER_DATA_ID"))
(def target-percent (get-int "TARGET_PERCENT"))
(def target-time-hour (get-int "TARGET_TIME_HOUR"))
(def target-time-minute (get-int "TARGET_TIME_MINUTE"))
(def thingspeak-api-key (get-env "THINGSPEAK_API_KEY"))
