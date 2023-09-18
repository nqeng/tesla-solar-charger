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

(defn round-down-to-minute-interval
  [datetime interval-minutes]
  (let [minutes (.getMinute datetime)
        new-minutes (* interval-minutes (Math/floor (/ minutes interval-minutes)))]
    (-> datetime
        (.withMinute new-minutes)
        (.withSecond 0))))

(defn round-up-to-minute-interval
  [datetime interval-minutes]
  (let [hours (.getHour datetime)
        minutes (.getMinute datetime)
        seconds (.getSecond datetime)
        [minutes seconds] (if (> seconds 0) [(+ 1 minutes) 0] [minutes seconds])
        minutes (* interval-minutes (Math/ceil (/ minutes interval-minutes)))
        [hours minutes] (if (>= minutes 60) [(+ 1 hours) 0] [hours minutes])]
    (-> datetime
        (.withHour hours)
        (.withMinute minutes)
        (.withSecond seconds)
        (.withNano 0))))

(defn get-latest-data-publish-time
  []
  (round-down-to-minute-interval (java.time.LocalDateTime/now) data-interval-minutes))

(defn get-last-data-timestamp
  ([datetime]
   (-> datetime
       (round-down-to-minute-interval data-interval-minutes)
       (create-data-point-timestamp)))
  ([]
   (-> (java.time.LocalDateTime/now)
       (round-down-to-minute-interval data-interval-minutes)
       (create-data-point-timestamp))))

(defn get-next-data-publish-time
  [datetime]
  (-> datetime
      (round-up-to-minute-interval data-interval-minutes)))

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
               {:type :err-sungrow-login-auth}))
       (= "-1" login-state)
       (throw (ex-info
               "Sungrow login failed; invalid username"
               {:type :err-sungrow-login-auth}))
       (= "2" login-state)
       (throw (ex-info
               "Sungrow login failed; too many failed attempts"
               {:type :err-sungrow-login-auth}))
       (= "E916" error-code)
       (throw (ex-info
               "Sungrow login failed; Login too frequently"
               {:type :err-sungrow-login-too-frequent}))
       (= "009" error-code)
       (throw (ex-info
               "Sungrow login failed; Username or password empty"
               {:type :err-sungrow-login-auth}))
       :else
       (throw (ex-info
               (str "Sungrow login failed; " error)
               {:type :err-sungrow-login-other})))))

  ([]
   (login env/sungrow-username env/sungrow-password)))

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
                          :start_time_stamp (get-last-data-timestamp start-time)
                          :end_time_stamp (get-last-data-timestamp end-time)
                          :minute_interval data-interval-minutes
                          :ps_key (str/join "," ps-keys)
                          :points (str/join "," points)}
            :content-type :json})
          (catch java.net.UnknownHostException e
            (let [error (.getMessage e)]
              (throw (ex-info
                      (str "Network error; " error)
                      {:type :network-error}))))
          (catch java.net.NoRouteToHostException e
            (let [error (.getMessage e)]
              (throw (ex-info
                      (str "Network error; " error)
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

(defn get-power-to-grid
  ([token time grid-sensor-device meter-active-power]
   (let [json-data (get-data
                    token
                    time
                    time
                    [grid-sensor-device meter-active-power])
         power-value-str (get-in json-data [grid-sensor-device
                                            meter-active-power
                                            (get-last-data-timestamp time)] "--")]
     (if (= "--" power-value-str)
       nil
       (- (Float/parseFloat power-value-str)))))
  ([token time]
   (get-power-to-grid
    token
    time
    env/grid-sensor-device-id
    env/grid-power-data-id)))

