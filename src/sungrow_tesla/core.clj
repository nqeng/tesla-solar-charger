(ns sungrow-tesla.core
  (:gen-class)
  (:require
   [clojure.string :as str]
   [clj-http.client :as client]
   [cheshire.core :as json]
   [lambdaisland.dotenv :as dotenv]))

(def sungrow-api-key "93D72E60331ABDCDC7B39ADC2D1F32B3")
(def data-interval-minutes 5)  ; Time interval between data points

(def env
  (let [env (dotenv/parse-dotenv (slurp ".env"))]
    {:sungrow-username (get env "SUNGROW_USERNAME")
     :sungrow-password (get env "SUNGROW_PASSWORD")
     :tessie-token (get env "TESSIE_ACCESS_TOKEN")
     :tesla-vin (get env "TESLA_VIN")
     :power-buffer-watts (Float/parseFloat (get env "POWER_BUFFER_WATTS"))
     :max-climb-amps (Integer/parseInt (get env "MAX_CLIMB_AMPS"))
     :max-drop-amps (Integer/parseInt (get env "MAX_DROP_AMPS"))
     :charger-geoboundary {:north (Float/parseFloat (get env "CHARGER_BOUNDARY_NORTH"))
                           :south (Float/parseFloat (get env "CHARGER_BOUNDARY_SOUTH"))
                           :east (Float/parseFloat (get env "CHARGER_BOUNDARY_EAST"))
                           :west (Float/parseFloat (get env "CHARGER_BOUNDARY_WEST"))}
     :grid-sensor-device-id (get env "GRID_SENSOR_DEVICE_ID")
     :grid-power-data-point-id (get env "GRID_POWER_DATA_POINT_ID")
     :charge-override-song (get env "CHARGE_OVERRIDE_SONG")}))

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
  ([username password]
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
  ([]
   (login-to-sungrow (:sungrow-username env) (:sungrow-password env))))

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
                "Failed to retrieve Sungrow data; authentication error"
                {:type :err-sungrow-not-logged-in}))
        data))
    (catch clojure.lang.ExceptionInfo e
      (throw e))
    (catch Exception e
      (throw (ex-info
              (str "Failed to retrieve Sungrow data; " (.getMessage e))
              {:type :err-could-not-get-sungrow-data})))))

(defn get-sungrow-power-to-grid
  ([auth-token data-timestamp grid-sensor-device meter-active-power]
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
       (- (Float/parseFloat power-value-str)))))
  ([auth-token data-timestamp]
   (get-sungrow-power-to-grid
    auth-token
    data-timestamp
    (:grid-sensor-device-id env)
    (:grid-power-data-point-id env))))

(defn limit
  [num min max]
  (cond
    (> num max) max
    (< num min) min
    :else num))

(defn get-tesla-state
  ([tesla-vin tessie-access-token]
   (try
     (let [response (client/get
                     (str "https://api.tessie.com/" tesla-vin "/state")
                     {:oauth-token tessie-access-token
                      :accept :json})
           json (json/parse-string (:body response))]
       json)
     (catch Exception e
       (ex-info
        (str "Failed to get Tesla state; " (.getMessage e))
        {:type :err-could-not-get-tesla-state}))))
  ([]
   (get-tesla-state (:tesla-vin env) (:tessie-token env))))

(defn update-tesla-charge-amps
  ([charge-speed-amps tesla-vin tessie-token]
   (try (client/get
         (str "https://api.tessie.com/" tesla-vin "/command/set_charging_amps")
         {:oauth-token tessie-token
          :query-params {:retry-duration "40"
                         :wait-for-completion "true"
                         :amps (str charge-speed-amps)}
          :accept :json})
        (catch Exception e
          (ex-info
           (str "Failed to set Tesla charging amps; " (.getMessage e))
           {:type :err-could-not-set-charge-amps}))))
  ([charge-speed-amps]
   (update-tesla-charge-amps charge-speed-amps (:tesla-vin env) (:tessie-token env))))

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

(defn get-tesla-battery-level
  [tesla-state]
  (get-in tesla-state ["charge_state" "battery_level"]))

(defn is-tesla-in-valet-mode?
  [tesla-state]
  (get-in tesla-state ["vehicle_state" "valet_mode"]))

(defn is-charge-override-on?
  [tesla-state]
  (is-tesla-in-valet-mode? tesla-state))

(defn get-latest-data-point
  [time]
  (format-datetime (.withSecond (.withMinute time (* data-interval-minutes (Math/floor (/ (.getMinute time) (float data-interval-minutes))))) 0)))

(defn is-tesla-at-office?
  ([tesla-state charger-geoboundary]
   (let [latitude (get-tesla-latitude tesla-state)
         longitude (get-tesla-longitude tesla-state)]
     (and
      (< latitude (:north charger-geoboundary))
      (> latitude (:south charger-geoboundary))
      (< longitude (:east charger-geoboundary))
      (> longitude (:west charger-geoboundary)))))
  ([tesla-state]
   (is-tesla-at-office? tesla-state (:charger-geoboundary env))))

(defn has-fresh-data-point?
  [program-state]
  (not= (:last-data-point-handled program-state) (get-latest-data-point (:time program-state))))

(defn has-tesla-been-charging?
  [program-state]
  ((complement nil?) (:last-data-point-handled program-state)))

(defn calc-new-charge-amps
  [power-to-grid-watts
   power-buffer-watts
   tesla-charge-amps
   max-climb-amps
   max-drop-amps
   tesla-max-amps]
  (let [available-power (- power-to-grid-watts power-buffer-watts)
        adjustment-amps (-> (/ available-power 687.5)
                            (limit (- max-drop-amps) max-climb-amps))
        new-charge-amps (-> (+ tesla-charge-amps adjustment-amps)
                            (float)
                            (Math/round)
                            (int)
                            (limit 0 tesla-max-amps))]
    new-charge-amps))

; TODO: 
; - run-program performs side-effects and returns new state
; - status message
; - login to sungrow error

(defn tesla-just-disconnected?
  [program-state tesla-state]
  (and (not (is-tesla-charging? tesla-state))
       (has-tesla-been-charging? program-state)))

(defn tesla-just-connected?
  [program-state tesla-state]
  (and (is-tesla-charging? tesla-state)
       (not (has-tesla-been-charging? program-state))))

(defn create-status-message
  [latest-data-point
   power-to-grid-watts
   new-charge-amps
   current-charge-amps
   battery-level
   power-buffer-watts]
  (format "Data point time: %s
Power feeding to grid: %.2fW
Power available to Tesla: %.2fW
Tesla charge speed: %dA (%s%dA)
Tesla power draw: %.2fW
Battery level: %d%%"
          latest-data-point
          power-to-grid-watts
          (- power-to-grid-watts power-buffer-watts)
          new-charge-amps
          (if (pos? (- new-charge-amps current-charge-amps)) "+" "-")
          (abs (- new-charge-amps current-charge-amps))
          (* new-charge-amps 687.5)
          (battery-level)))

(defn run-program
  "Executes actions based on current program state, returning the new state"
  [state]
  (try
    (lazy-let
     [latest-data-point (get-latest-data-point (:time state))
      tesla-state (get-tesla-state)
      current-charge-amps (get-tesla-charge-amps tesla-state)
      tesla-max-amps (get-tesla-max-amps tesla-state)
      tesla-battery-level (get-tesla-battery-level tesla-state)
      power-to-grid-watts (get-sungrow-power-to-grid
                           (:sungrow-token state)
                           latest-data-point)
      new-charge-amps (calc-new-charge-amps
                       power-to-grid-watts
                       (:power-buffer-watts env)
                       current-charge-amps
                       (:max-climb-amps env)
                       (:max-drop-amps env)
                       tesla-max-amps)]
     (cond

       (not (is-tesla-at-office? tesla-state))
       (assoc state
              :message "Tesla is not at the NQE office"
              :delay 60000)
       ; Tesla is not charging and has been charging previously
       (tesla-just-disconnected? state tesla-state)
       (assoc state
              :message "Tesla disconnected; reset charge amps to max"
              :delay 10000
              :last-data-point-handled nil
              :new-charge-amps (get-tesla-max-amps tesla-state))
       ; Tesla is not charging and hasn't been charging previously
       (not (is-tesla-charging? tesla-state))
       (assoc state
              :message "Tesla is not charging"
              :delay 5000)
       ; If max charge speed override is in place
       (is-charge-override-on? tesla-state)
       (assoc state
              :message (str "Charge speed overridden; charging at " tesla-max-amps "A")
              :last-data-point-handled latest-data-point
              :new-charge-amps tesla-max-amps)
       ; Tesla is charging and hasn't been charging previously,
       ; begin charging at zero amps
       (tesla-just-connected? state tesla-state)
       (assoc state
              :message "Tesla connected; started charging at 0A"
              :last-data-point-handled latest-data-point
              :new-charge-amps 0)

       (not (has-fresh-data-point? state))
       state

       (nil? power-to-grid-watts)
       (assoc state
              :delay 40000)
       ; If charge amps haven't changed, don't update Tesla
       (= new-charge-amps current-charge-amps)
       (assoc state
              :message (create-status-message
                        latest-data-point
                        power-to-grid-watts
                        new-charge-amps
                        current-charge-amps
                        tesla-battery-level
                        (:power-buffer-watts env))
              :last-data-point-handled latest-data-point)
       :else
       (assoc state
              :message (create-status-message
                        latest-data-point
                        power-to-grid-watts
                        new-charge-amps
                        current-charge-amps
                        tesla-battery-level
                        (:power-buffer-watts env))
              :last-data-point-handled latest-data-point
              :new-charge-amps new-charge-amps)))

    (catch clojure.lang.ExceptionInfo e
      (case (:type (ex-data e))
        :err-could-not-get-sungrow-data (assoc state :message (ex-message e))
        :err-could-not-get-tesla-state (assoc state :message (ex-message e))
        :err-sungrow-not-logged-in (assoc state :message (ex-message e) :sungrow-token nil)
        :err-could-not-login-to-sungrow (throw e)
        (throw e)))))

(defn relogin-to-sungrow-if-needed
  ([program-state sungrow-username sungrow-password]
   (if (nil? (:sungrow-token program-state))
     (login-to-sungrow sungrow-username sungrow-password)
     (:sungrow-token program-state)))
  ([program-state]
   (relogin-to-sungrow-if-needed
    program-state
    (:sungrow-username env)
    (:sungrow-password env))))

(defn -main
  [& args]
  (println "Starting...")
  (let [initial-state {:time (time-now)
                       :last-data-point-handled nil
                       :delay 0
                       :sungrow-token (login-to-sungrow)
                       :new-charge-amps nil}]
    (loop [state initial-state]
      (let [new-state (run-program state)]
        (if (:message new-state)
          (println (:message new-state))
          nil)
        (if (:new-charge-amps new-state)
          (update-tesla-charge-amps (:new-charge-amps new-state))
          nil)
        (Thread/sleep (:delay new-state))

        (recur (assoc new-state
                      :time (time-now)
                      :message nil
                      :delay 0
                      :sungrow-token (relogin-to-sungrow-if-needed new-state)
                      :new-charge-amps nil))))))



