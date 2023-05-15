(ns sungrow-tesla.sungrow
  (:require
   [dotenv :refer [env]]
   [clojure.string :as str]
   [clj-http.client :as client]
   [cheshire.core :as json]))

(def sungrow-api-key "93D72E60331ABDCDC7B39ADC2D1F32B3")
(def data-interval-minutes 5)  ; Time interval between data points

(defn format-datetime
  "15/05/2023:14:00:00 => 20230515140000"
  [datetime]
  (.format
   datetime
   (java.time.format.DateTimeFormatter/ofPattern "yyyyMMddHHmmss")))

(defn get-most-recent-minute-interval
  "Returns the datetime at the most recent multiple of n minutes.
  Example with interval of 5:
  12:46:01 => 12:45:00
  13:40:55 => 13:40:00
  14:00:00 => 14:00:00"
  [datetime interval-minutes]
  (let [minutes (.getMinute datetime)
        new-minutes (* interval-minutes (Math/floor (/ minutes interval-minutes)))]
    (-> datetime
        (.withMinute new-minutes)
        (.withSecond 0))))

(defn get-most-recent-data-timestamp
  [datetime]
  (-> datetime
      (get-most-recent-minute-interval data-interval-minutes)
      (format-datetime)))

(defn has-fresh-data-point?
  [program-state]
  (-> (:time program-state)
      (get-most-recent-data-timestamp)
      (not= (:last-data-point-timestamp program-state))))

(defn login
  "Sends a login request to Sungrow API, returning an auth token.
  Overloaded to use environment variables."
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
           (catch clojure.lang.ExceptionInfo e
             (throw (ex-info
                     (str "Failed to login to Sungrow; " (ex-message e))
                     {:type :err-could-not-login-to-sungrow}))))
         json (json/parse-string (:body response))
         login-state (get-in json ["result_data" "login_state"])
         user-id (get-in json ["result_data" "user_id"])
         token (get-in json ["result_data" "token"])]
     (case login-state
       "1" token
       "2" (throw (ex-info
                   (str "Sungrow login failed; too many failed attempts")
                   {:type :err-too-many-failed-login-attempts}))
       nil (throw (ex-info
                   (str "Sungrow login failed; logging in too frequently")
                   {:type :err-logging-in-too-frequently}))
       (throw (ex-info
               (str "Sungrow login failed; an unknown error occurred")
               {:type :err-could-not-login-to-sungrow})))))
  ([]
   (login (env "SUNGROW_USERNAME") (env "SUNGROW_PASSWORD"))))

(defn request-data
  [token start-timestamp end-timestamp data-devices data-points]
  (let [response
        (try
          (client/post
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
          (catch clojure.lang.ExceptionInfo e
            (throw (ex-info
                    (str "Sungrow request failed; " (ex-message e))
                    {:type :err-could-not-get-sungrow-data}))))
        json (json/parse-string (:body response))
        data (get json "result_data")
        error (get json "result_msg")
        code (get json "result_code")]
    (cond
      (not (nil? data)) data
      (and (= "1" code) (= "success" error))
      (throw (ex-info
              "Sungrow data request failed; invalid token or device/data ids"
              {:type :err-could-not-get-sungrow-data}))
      (and (= "009" code) (= "er_missing_parameter:user_id" error))
      (throw (ex-info
              "Sungrow data request failed; no token or user id provided"
              {:type :err-could-not-get-sungrow-data}))
      (= "009" code)
      (throw (ex-info
              "Sungrow data request failed; missing device ids or data point ids"
              {:type :err-could-not-get-sungrow-data}))
      :else
      (throw (ex-info
              (str "Sungrow data request failed; " error)
              {:type :err-could-not-get-sungrow-data})))))

(defn get-power-to-grid
  ([auth-token data-timestamp grid-sensor-device meter-active-power]
   (let [json-data (request-data
                    auth-token
                    data-timestamp
                    data-timestamp
                    [grid-sensor-device]
                    [meter-active-power])
         power-value-str (get-in json-data [grid-sensor-device meter-active-power data-timestamp] "--")]
     (if (= "--" power-value-str)
       nil
       (- (Float/parseFloat power-value-str)))))
  ([auth-token data-timestamp]
   (get-power-to-grid
    auth-token
    data-timestamp
    (env "GRID_SENSOR_DEVICE_ID")
    (env "GRID_POWER_DATA_ID"))))

