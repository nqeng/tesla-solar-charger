(ns tesla-solar-charger.sungrow
  (:require
   [clojure.string :as str]
   [clj-http.client :as client]
   [cheshire.core :as json]
   [tesla-solar-charger.env :as env]))

(def api-key "93D72E60331ABDCDC7B39ADC2D1F32B3")
(def data-interval-minutes 5)  ; Time interval between data points

(defn create-data-point-timestamp
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

(defn get-latest-data-timestamp
  [datetime]
  (-> datetime
      (get-most-recent-minute-interval data-interval-minutes)
      (create-data-point-timestamp)))

(defn login
  "Sends a login request to Sungrow API, returning an auth token.
  Overloaded to use environment variables."
  ([username password]
   (let [response
         (client/post
          "https://augateway.isolarcloud.com/v1/userService/login"
          {:form-params {:appkey api-key
                         :sys_code "900"
                         :user_account username
                         :user_password password}
           :content-type :json})
         json (json/parse-string (:body response))
         login-state (get-in json ["result_data" "login_state"])
         tries-left (get-in json ["result_data" "remain_times"])
         error (get json "result_msg")
         error-code (get json "result_code")
         user-id (get-in json ["result_data" "user_id"])
         token (get-in json ["result_data" "token"])]
     (cond
       (= "1" login-state)
       token
       (= "0" login-state)
       (throw (ex-info
               (str "Sungrow login failed; authentication error (" tries-left " tries left)")
               {:type :err-sungrow-auth-failure}))
       (= "-1" login-state)
       (throw (ex-info
               "Sungrow login failed; invalid username"
               {:type :err-sungrow-auth-failure}))
       (= "2" login-state)
       (throw (ex-info
               "Sungrow login failed; too many failed attempts"
               {:type :err-sungrow-auth-failure}))
       (= "E916" error-code)
       (throw (ex-info
               "Sungrow login failed; Login too frequently"
               {:type :err-sungrow-login-too-frequently}))
       (= "009" error-code)
       (throw (ex-info
               "Sungrow login failed; Username or password empty"
               {:type :err-sungrow-auth-failure}))
       :else
       (throw (ex-info
               (str "Sungrow login failed; " error)
               {:type :err-sungrow-login-failed})))))

  ([]
   (login env/sungrow-username env/sungrow-password)))

(defn create-status-message
  ([data-timestamp
    power-to-grid-watts
    power-buffer-watts]
   (format "Data point timestamp: %s
Power feeding to grid: %.2fW
Power available to Tesla: %.2fW"
           data-timestamp
           (float power-to-grid-watts)
           (float (- power-to-grid-watts power-buffer-watts))))
  ([data-timestamp
    power-to-grid-watts]
   (create-status-message
    data-timestamp
    power-to-grid-watts
    env/power-buffer-watts)))

(defn send-data-request
  [token start-timestamp end-timestamp data-devices data-points]
  (client/post
   "https://augateway.isolarcloud.com/v1/commonService/queryMutiPointDataList"
   {:form-params {:appkey api-key
                  :sys_code "200"
                  :token token
                  :user_id ""
                  :start_time_stamp start-timestamp
                  :end_time_stamp end-timestamp
                  :minute_interval data-interval-minutes
                  :ps_key (str/join "," data-devices)
                  :points (str/join "," data-points)}
    :content-type :json}))

(defn get-data
  [token start-time end-time & data-points]
  (let [ps-keys (map first data-points)
        points (map second data-points)
        response
        (try
          (client/post
           "https://augateway.isolarcloud.com/v1/commonService/queryMutiPointDataList"
           {:form-params {:appkey api-key
                          :sys_code "200"
                          :token token
                          :user_id ""
                          :start_time_stamp (get-latest-data-timestamp start-time)
                          :end_time_stamp (get-latest-data-timestamp end-time)
                          :minute_interval data-interval-minutes
                          :ps_key (str/join "," ps-keys)
                          :points (str/join "," points)}
            :content-type :json})
          (catch java.net.UnknownHostException e
            (let [error (.getMessage e)]
              (throw (ex-info
                      (str "Failed to get Tesla state; " error)
                      {:type :network-error}))))
          (catch java.net.NoRouteToHostException e
            (let [error (.getMessage e)]
              (throw (ex-info
                      (str "Failed to get Tesla state; " error)
                      {:type :network-error})))))
        json (json/parse-string (:body response))
        data (get json "result_data")
        error (get json "result_msg")
        code (get json "result_code")]
    (cond
      (not (nil? data))
      data
      (and (= "1" code) (= "success" error))
      (throw (ex-info
              "Sungrow data request failed; invalid token or device/data ids"
              {:type :err-sungrow-auth-failed}))
      (and (= "009" code) (= "er_missing_parameter:user_id" error))
      (throw (ex-info
              "Sungrow data request failed; no token or user id provided"
              {:type :err-sungrow-auth-failed}))
      (= "009" code)
      (throw (ex-info
              "Sungrow data request failed; missing device ids or data point ids"
              {:type :err-could-not-get-sungrow-data}))
      :else
      (throw (ex-info
              (str "Sungrow data request failed; " error)
              {:type :err-could-not-get-sungrow-data})))))

(require '[clj-http.client :as client])

(login "reuben@nqeng.com.au" "absdq142")

(get-latest-data-timestamp (java.time.LocalDateTime/now))

(get-data
 (login "reuben@nqeng.com.au" "absdq142")
 (.minusMinutes (java.time.LocalDateTime/now) 30)
 (java.time.LocalDateTime/now)
 ["1152381_7_2_3" "p8018"]
 ["1152381_7_2_3" "p8000"])

(defn get-power-to-grid
  ([token time grid-sensor-device meter-active-power]
   (let [json-data (get-data
                    token
                    time
                    time
                    [grid-sensor-device meter-active-power])
         power-value-str (get-in json-data [grid-sensor-device
                                            meter-active-power
                                            (get-latest-data-timestamp time)] "--")]
     (if (= "--" power-value-str)
       nil
       (- (Float/parseFloat power-value-str)))))
  ([token time]
   (get-power-to-grid
    token
    time
    env/grid-sensor-device-id
    env/grid-power-data-id)))

(get-power-to-grid
 (login "reuben@nqeng.com.au" "absdq142")
 (.minusMinutes (java.time.LocalDateTime/now) 30)
 "1152381_7_2_3"
 "p8018")

