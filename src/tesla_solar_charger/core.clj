(ns tesla-solar-charger.core
  (:gen-class)
  (:require
    [clojure.tools.cli :refer [parse-opts]]
    [tesla-solar-charger.car-charge-setter.tessie-charge-setter :refer [new-TessieChargeSetter]]
    [tesla-solar-charger.regulator.simple-regulator :refer [new-SimpleRegulator]]
    [tesla-solar-charger.recorder.csv-recorder :refer [new-CSVRecorder]]
    [tesla-solar-charger.gophers.get-car-state :refer [fetch-new-car-state]]
    [tesla-solar-charger.gophers.set-charge-rate :refer [set-charge-rate]]
    [tesla-solar-charger.gophers.record-data :refer [record-data]]
    [better-cond.core :refer [cond] :rename {cond better-cond}]
    [tesla-solar-charger.utils :as utils]
    [clj-http.client :as client]
    [taoensso.timbre :as timbre]
    [clojure.java.io :refer [make-parents]]
    [taoensso.timbre.appenders.core :as appenders]
    [tesla-solar-charger.gophers.regulate-charge-rate :refer [regulate-charge-rate]]
    [tesla-solar-charger.gophers.utils :refer [sliding-buffer split-ch split-channel keep-last-value print-values] :rename {sliding-buffer my-sliding-buffer}]
    [tesla-solar-charger.car-data-source.tessie-data-source :refer [new-TessieDataSource]]
    [tesla-solar-charger.solar-data-source.gosungrow-data-source :refer [new-GoSungrowDataSource]]
    [tesla-solar-charger.gophers.get-solar-data :refer [fetch-new-solar-data]]
    [clojure.core.async :as async :refer [close! <!! chan sliding-buffer]]))

(defn getenv
  [key]
  (let [value (System/getenv key)]
    (when (nil? value)
      (timbre/errorf "[Main] Environment variable %s is not defined" key))
    value))

(defn ntfy
  [channel-name message]
  (let [url (format "https://ntfy.sh/%s" channel-name)]
    (try
      (client/post url {:body message})
      (catch Exception _)
      (catch clojure.lang.ExceptionInfo _))))

(defn make-ntfy-appender
  [ntfy-channel-name]
  {:min-level :warn
   :enabled? true
   :async? true
   :fn (fn [data] 
         (let [{:keys [vargs]} data
               message (apply str vargs)]
           (ntfy ntfy-channel-name message)))})

(defn add-ntfy-appender
  [ntfy-channel-name]
  (timbre/merge-config! {:appenders {:ntfy (make-ntfy-appender ntfy-channel-name)}}))

(defn delete-log-file
  [log-filepath]
  (try 
    (clojure.java.io/delete-file log-filepath)
    (catch java.io.IOException _)))

(defn add-spit-appender
  [log-filename]
  (timbre/merge-config!
    {:appenders {:spit (appenders/spit-appender {:fname log-filename})}}))

(defn -main
  [& args]
  (timbre/warn "[Main] Starting...")
  (let [kill-ch (chan)
        shutdown-hook (fn [] (timbre/warn "[Main] Sending kill signal...") (close! kill-ch))

        log-filename "logs.log"

        _ (add-spit-appender log-filename)

        _ (delete-log-file log-filename)

        tesla-vin (getenv "TESLA_VIN")
        tessie-auth-token (getenv "TESSIE_AUTH_TOKEN")

        car-data-source (new-TessieDataSource tesla-vin tessie-auth-token)
        charge-setter (new-TessieChargeSetter tessie-auth-token tesla-vin)

        office-csv-recorder (new-CSVRecorder "./office.csv")
        home-csv-recorder (new-CSVRecorder "./home.csv")

        sungrow-username (getenv "SUNGROW_USERNAME")
        sungrow-password (getenv "SUNGROW_PASSWORD")

        office-solar-data-source (new-GoSungrowDataSource 
                                   "./GoSungrow-office"
                                   "B0455FBE7AA0328DB57B59AA729F05D8"
                                   sungrow-username 
                                   sungrow-password 
                                   "1152381"
                                   "1152381_7_2_3"
                                   "p8018")

        home-solar-data-source (new-GoSungrowDataSource 
                                 "./GoSungrow-home"
                                 "B0455FBE7AA0328DB57B59AA729F05D8"
                                 sungrow-username 
                                 sungrow-password 
                                 "1256712"
                                 "1256712_7_1_1"
                                 "p8018")

        office-latitude (parse-double (getenv "OFFICE_LATITUDE"))
        office-longitude (parse-double (getenv "OFFICE_LONGITUDE"))
        office-location {:latitude office-latitude 
                         :longitude office-longitude 
                         :name "Office"}

        home-latitude (parse-double (getenv "HOME_LATITUDE"))
        home-longitude (parse-double (getenv "HOME_LONGITUDE"))
        home-location {:latitude home-latitude 
                       :longitude home-longitude 
                       :name "Home"}

        tesla-state-ch (chan)
        tesla-state-ch2 (chan)
        tesla-state-ch3 (chan)
        tesla-state-ch4 (chan)
        tesla-state-ch5 (chan)

        office-solar-data-ch (chan)
        office-solar-data-ch2 (chan)
        office-solar-data-ch3 (chan)

        home-solar-data-ch (chan)
        home-solar-data-ch2 (chan)
        home-solar-data-ch3 (chan)

        charge-power-ch (chan (sliding-buffer 1))

        office-regulator (new-SimpleRegulator 
                           "Tesla"
                           office-location 
                           1000
                           1000
                           1000
                           "Office Regulator")

        home-regulator (new-SimpleRegulator 
                         "Tesla"
                         home-location 
                         1000
                         1000
                         1000
                         "Home Regulator")]

    (.addShutdownHook (Runtime/getRuntime) (Thread. shutdown-hook))

    (split-ch tesla-state-ch tesla-state-ch2 tesla-state-ch3 tesla-state-ch4 tesla-state-ch5)
    (split-ch home-solar-data-ch home-solar-data-ch2 home-solar-data-ch3)
    (split-ch office-solar-data-ch office-solar-data-ch2 office-solar-data-ch3)

    (fetch-new-car-state car-data-source tesla-state-ch kill-ch "Tessie Data Source")

    (fetch-new-solar-data office-solar-data-source office-solar-data-ch kill-ch "Office Data Source")

    (fetch-new-solar-data home-solar-data-source home-solar-data-ch kill-ch "Home Data Source")

    (regulate-charge-rate office-regulator tesla-state-ch2 office-solar-data-ch2 charge-power-ch kill-ch "Office Regulator")

    (regulate-charge-rate home-regulator tesla-state-ch3 home-solar-data-ch2 charge-power-ch kill-ch "Home Regulator")

    (record-data office-csv-recorder office-location tesla-state-ch4 office-solar-data-ch3 kill-ch "Office Recorder")

    (record-data home-csv-recorder home-location tesla-state-ch5 home-solar-data-ch3 kill-ch "Home Recorder")

    (set-charge-rate charge-setter charge-power-ch kill-ch "Tessie Charge Setter")

    (<!! kill-ch)

    (timbre/shutdown-appenders!)
    (shutdown-agents)))

