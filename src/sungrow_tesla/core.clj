(ns sungrow-tesla.core
  (:gen-class)
  (:require
   [clojure.string :as str]
   [clj-http.client :as client]
   [cheshire.core :as json]
   [lambdaisland.dotenv :as dotenv]))

(def sungrow-api-key "93D72E60331ABDCDC7B39ADC2D1F32B3")
(def data-interval-minutes 5)  ; Time interval between data points
(def env (dotenv/parse-dotenv (slurp ".env")))

(defn replace-symbols
  "Given a map of replacement pairs and a form, returns a (nested)
  form with any symbol = a key in smap replaced with the corresponding
  val in smap."
  [smap form]
  (if (sequential? form)
    (map (partial replace-symbols smap) form)
    (get smap form form)))

(defmacro lazy-let
  "A lazy version of let. It doesn't evaluate any bindings until they
  are needed. No more nested lets and it-lets when you have many
  conditional bindings."
  [bindings & body]
  (let [locals (take-nth 2 bindings)
        local-forms (take-nth 2 (rest bindings))
        smap (zipmap locals (map (fn [local] `(first ~local)) locals))
        bindings (->> (map (fn [lf]
                             `(lazy-seq (list ~(replace-symbols smap lf))))
                           local-forms) (interleave locals) vec)
        body (replace-symbols smap body)]
    (conj body bindings 'let)))

(defn format-datetime
  [datetime]
  (.format
   datetime
   (java.time.format.DateTimeFormatter/ofPattern "yyyyMMddHHmmss")))

(defn time-now
  []
  (java.time.LocalDateTime/now))

(defn login-to-sungrow
  [username password]
  (let [response
        (try
          (client/post
           "https://augateway.isolarcloud.com/v1/userService/login"
           {:form-params {:appkey sungrow-api-key
                          :sys_code "900"
                          :user_account username
                          :user_password password}
            :content-type :json})
          (catch Exception e
            (throw (ex-info
                    (str "Failed to login to Sungrow; " (.getMessage e))
                    {:type :err-could-not-login-to-sungrow}))))
        json (json/parse-string (:body response))
        login-state (get-in json ["result_data" "login_state"])
        user-id (get-in json ["result_data" "user_id"])
        token (get-in json ["result_data" "token"])]
    (case login-state
      "2" (throw (ex-info
                  (str "Failed to login to Sungrow; error code " login-state ": too many failed attempts")
                  {:type :err-could-not-login-to-sungrow}))
      "1" token
      nil (throw (ex-info
                  (str "Failed to login to Sungrow; logging in too frequently")
                  {:type :err-could-not-login-to-sungrow}))
      (throw (ex-info
              (str "Failed to login to Sungrow; error code " login-state ": an unknown error occurred")
              {:type :err-could-not-login-to-sungrow})))))

(defn request-sungrow-data
  [token start-timestamp end-timestamp data-devices data-points]
  (try
    (let [response (client/post
                    "https://augateway.isolarcloud.com/v1/commonService/queryMutiPointDataList"
                    {:form-params {:appkey sungrow-api-key
                                   :sys_code "200"
                                   :token token
                                   :user_id ""
                                   :start_time_stamp start-timestamp
                                   :end_time_stamp end-timestamp
                                   :minute_interval data-interval-minutes
                                   :ps_key (str/join "," data-devices)
                                   :points (str/join "," data-points)}
                     :content-type :json})
          json (json/parse-string (:body response))
          data (get json "result_data")]
      (if (nil? data)
        (throw (ex-info
                "authentication failure"
                {:type :err-sungrow-not-logged-in}))
        data))
    (catch Exception e
      (throw (ex-info
              (str "Failed to retrieve Sungrow data; " (.getMessage e))
              {:type :err-could-not-get-sungrow-data})))))

(defn get-sungrow-power-to-grid
  [auth-token data-timestamp grid-sensor-device meter-active-power]
  (let [json-data (request-sungrow-data
                   auth-token
                   data-timestamp
                   data-timestamp
                   [grid-sensor-device]
                   [meter-active-power]
                   ;;
                   )
        power-value-str (get-in
                         json-data
                         [grid-sensor-device meter-active-power data-timestamp]
                         "--")]
    (if (= "--" power-value-str)
      nil
      (Float/parseFloat power-value-str))))

(defn limit
  [charge-speed-amps min max]
  (if (> charge-speed-amps max)
    max
    (if (< charge-speed-amps min)
      min
      charge-speed-amps)))

(defn get-tesla-state
  [tesla-vin tessie-access-token]
  (try
    (let [response (client/get
                    (str "https://api.tessie.com/" tesla-vin "/state")
                    {:oauth-token tessie-access-token
                     :accept, :json})
          json (json/parse-string (:body response))]
      json)
    (catch Exception e
      (ex-info
       (str "Failed to get Tesla state; " (.getMessage e))
       {:type :err-could-not-get-tesla-state}))))

(defn update-tesla-charge-amps
  [charge-speed-amps tesla-vin tessie-access-token]
  (try (client/get
        (str "https://api.tessie.com/" tesla-vin "/command/set_charging_amps")
        {:oauth-token tessie-access-token
         :query-params {:retry-duration "40"
                        :wait-for-completion "true"
                        :amps (str charge-speed-amps)}
         :accept :json})
       (catch Exception e
         (ex-info
          (str "Failed to set Tesla charging amps; " (.getMessage e))
          {:type :err-could-not-set-charge-amps}))))

(defn get-status-message
  [{data-timestamp :data-timestamp
    grid-power-watts :grid-power-watts
    available-power :available-power
    amp-change :amp-change
    new-charge-amps :new-charge-amps
    tesla-vin :tesla-vin}]
  (format "%s%nPower to grid: %.2fW%nPower available: %.2fW (%s %.2fA)%nTesla will charge at %dA (%.2fW)%nTesla VIN: %s%n"
          data-timestamp
          grid-power-watts
          available-power (if (pos? amp-change) "+" "-") (abs amp-change)
          new-charge-amps (* new-charge-amps 687.5)
          tesla-vin))

(defn get-tesla-max-amps
  [tesla-state]
  (get-in tesla-state ["charge_state" "charge_current_request_max"]))

(defn get-tesla-charge-amps
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

(defn get-latest-data-point
  [time]
  (format-datetime (.withSecond (.withMinute time (* data-interval-minutes (Math/floor (/ (.getMinute time) (float data-interval-minutes))))) 0)))

(defn is-tesla-at-office?
  [tesla-state charger-geoboundary]
  (let [latitude (get-tesla-latitude tesla-state)
        longitude (get-tesla-longitude tesla-state)]
    (and
     (< latitude (:north charger-geoboundary))
     (> latitude (:south charger-geoboundary))
     (< longitude (:east charger-geoboundary))
     (> longitude (:west charger-geoboundary)))))

(defn get-charger-geoboundary
  []
  {:north (Float/parseFloat (get env "CHARGER_BOUNDARY_NORTH"))
   :south (Float/parseFloat (get env "CHARGER_BOUNDARY_SOUTH"))
   :east (Float/parseFloat (get env "CHARGER_BOUNDARY_EAST"))
   :west (Float/parseFloat (get env "CHARGER_BOUNDARY_WEST"))})

(defn has-fresh-data-point?
  [program-state]
  (not (= (:last-data-point-handled program-state) (get-latest-data-point (:time program-state)))))

(defn is-logged-in-to-sungrow?
  [program-state]
  (not (nil? (:token program-state))))

(defn just-started-charging?
  [program-state]
  (nil? (:last-data-point-handled program-state)))

(defn calc-new-charge-amps
  [power-to-grid-watts power-safety-buffer-watts tesla-charge-amps max-adjustment-amps tesla-max-amps]
  (let [available-power (- power-to-grid-watts power-safety-buffer-watts)
        adjustment-amps (limit (/ available-power 687.5) (- max-adjustment-amps) max-adjustment-amps)
        new-charge-amps (limit (int (Math/round (+ tesla-charge-amps adjustment-amps))) 0 tesla-max-amps)]
    new-charge-amps))

(defn run-program
  "Executes actions based on current program state, returning the new state"
  [state]
  (try
    (lazy-let [latest-data-point (get-latest-data-point (:time state))
          tesla-vin (get env "TESLA_VIN")
          tessie-token (get env "TESSIE_ACCESS_TOKEN")
          tesla-state (get-tesla-state tesla-vin tessie-token)
          tesla-max-amps (get-tesla-max-amps tesla-state)
          tesla-charge-amps (get-tesla-charge-amps tesla-state)
          charger-geoboundary (get-charger-geoboundary)
          grid-sensor-device-id (get env "GRID_SENSOR_DEVICE_ID")
          grid-power-data-point-id (get env "GRID_POWER_DATA_POINT_ID")
          power-safety-buffer-watts (Float/parseFloat (get env "POWER_SAFETY_BUFFER_WATTS"))
          max-adjustment-amps (Float/parseFloat (get env "MAX_ADJUSTMENT_AMPS"))
          power-to-grid-watts (get-sungrow-power-to-grid (:token state) latest-data-point grid-sensor-device-id grid-power-data-point-id)
          new-charge-amps (calc-new-charge-amps power-to-grid-watts power-safety-buffer-watts tesla-charge-amps max-adjustment-amps tesla-max-amps)]
      (cond
        (not (has-fresh-data-point? state))
        state

        (not (is-tesla-at-office? tesla-state charger-geoboundary))
        (assoc state :message "Tesla is not at the NQE office" :delay 60000)

        (not (is-tesla-charging? tesla-state))
        (do
          (update-tesla-charge-amps tesla-max-amps tesla-vin tessie-token)
          (assoc state :message "Tesla is not charging" :delay 10000))

        (just-started-charging? state)
        (do
          (update-tesla-charge-amps 0 tesla-vin tessie-token)
          (assoc state :message "Tesla connected; started charging at 0A" :last-data-point-handled latest-data-point))

        (nil? power-to-grid-watts)
        state

        :default
        (do
          (update-tesla-charge-amps new-charge-amps tesla-vin tessie-token)
          (assoc state :message (format "Tesla will charge at %.2fA and consume %.2fW%n" new-charge-amps (* new-charge-amps 687.5)) :last-data-point-handled latest-data-point))
        ))

    (catch clojure.lang.ExceptionInfo e
      (case (:type (ex-data e))
        :err-could-not-get-sungrow-data (assoc state :message (ex-message e) :token (login-to-sungrow (get env "SUNGROW_USERNAME") (get env "SUNGROW_PASSWORD")))
        :err-could-not-set-charge-amps (assoc state :message (ex-message e) :last-data-point-handled nil)
        :err-could-not-get-tesla-state (assoc state :message (ex-message e))
        :err-sungrow-not-logged-in (assoc state :token nil :message (ex-message e))
        :err-could-not-login-to-sungrow (throw e)
        (throw e)))))

(defn -main
  [& args]
  (println "Starting...")
  (let [initial-state {:time (time-now)
                       :last-data-point-handled nil
                       :delay 0
                       :token nil}]
    (loop [state initial-state]
      (let [new-state (run-program state)]
        (if (not (nil? (:message new-state)))
          (println (:message new-state)))
        (if (> (:delay new-state) 0)
          (Thread/sleep (:delay new-state)))
        (recur (assoc new-state :time (time-now) :message nil :delay 0))))))



