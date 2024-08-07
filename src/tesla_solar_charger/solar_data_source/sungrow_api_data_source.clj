(ns tesla-solar-charger.solar-data-source.sungrow-api-data-source
  (:require
   [tesla-solar-charger.utils :as utils]
   [tesla-solar-charger.solar-data-source.solar-data-source :as data-source]
   [cheshire.core :as json]
   [clj-http.client :as client]))

(defn create-data-point-timestamp
  "15/05/2023:14:00:00 => 20230515140000"
  [datetime]
  (.format
   datetime
   (java.time.format.DateTimeFormatter/ofPattern "yyyyMMddHHmmss")))

(defn round-up-to-minute-interval
  [datetime interval-minutes]
  (let [zone-offset (.getOffset (.getRules (java.time.ZoneId/systemDefault)) datetime)
        interval-seconds (* interval-minutes 60)
        seconds (.toEpochSecond datetime zone-offset)
        mod-seconds (mod seconds interval-seconds)
        new-seconds (+ seconds (- interval-seconds mod-seconds))
        new-time (java.time.LocalDateTime/ofEpochSecond new-seconds 0 zone-offset)]
    new-time))

(defn round-down-to-minute-interval
  [datetime interval-minutes]
  (let [zone-offset (.getOffset (.getRules (java.time.ZoneId/systemDefault)) datetime)
        interval-seconds (* interval-minutes 60)
        seconds (.toEpochSecond datetime zone-offset)
        mod-seconds (mod seconds interval-seconds)
        new-seconds (- seconds mod-seconds)
        new-time (java.time.LocalDateTime/ofEpochSecond new-seconds 0 zone-offset)]
    new-time))

(defn get-latest-data-publish-time
  [datetime data-interval-minutes]
  (-> datetime
      (round-down-to-minute-interval data-interval-minutes)))

(defn get-latest-data-timestamp
  [datetime data-interval-minutes]
  (-> datetime
      (get-latest-data-publish-time data-interval-minutes)
      create-data-point-timestamp))

(defn get-next-data-publish-time
  [datetime data-interval-minutes]
  (-> datetime
      (round-up-to-minute-interval data-interval-minutes)))

(defn login
  [username password api-key]
  (try
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
                {:type :err-sungrow-login-other}))))
    (catch java.net.UnknownHostException e
      (let [error (.getMessage e)]
        (throw (ex-info
                (str "Network error; " error)
                {:type :network-error}))))
    (catch java.net.NoRouteToHostException e
      (let [error (.getMessage e)]
        (throw (ex-info
                (str "Network error; " error)
                {:type :network-error}))))))

(defn get-data
  [token start-time end-time api-key data-interval-minutes & data-points]
  (try
    (let [ps-keys (map first data-points)
          points (map second data-points)
          response (client/post
                    "https://augateway.isolarcloud.com/v1/commonService/queryMutiPointDataList"
                    {:form-params {:appkey api-key
                                   :sys_code "200"
                                   :token token
                                   :user_id ""
                                   :start_time_stamp (get-latest-data-timestamp start-time data-interval-minutes)
                                   :end_time_stamp (get-latest-data-timestamp end-time data-interval-minutes)
                                   :minute_interval data-interval-minutes
                                   :ps_key (clojure.string/join "," ps-keys)
                                   :points (clojure.string/join "," points)}
                     :content-type :json})

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
                {:type :err-could-not-get-sungrow-data}))))
    (catch java.net.UnknownHostException e
      (let [error (.getMessage e)]
        (throw (ex-info
                (str "Network error; " error)
                {:type :network-error}))))
    (catch java.net.NoRouteToHostException e
      (let [error (.getMessage e)]
        (throw (ex-info
                (str "Network error; " error)
                {:type :network-error}))))))

(defrecord SungrowAPIDataSource []
  data-source/IDataSource
  (get-latest-data-point [data-source] 
    {:obj data-source :val nil :err (ex-info "Not implemented" {})}))

(defn new-SungrowAPIDataSource
  [username password app-key data-interval-minutes values]
  (let [the-map {:username username
                 :password password
                 :app-key app-key
                 :data-interval-minutes data-interval-minutes
                 :values values}
        defaults {:next-data-available-time (utils/time-now)}]
    (map->SungrowAPIDataSource (merge defaults the-map))))


