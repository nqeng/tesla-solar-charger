(ns tesla-solar-charger.core
  (:gen-class)
  (:require
    [clojure.tools.cli :refer [parse-opts]]
    [tesla-solar-charger.car-charge-setter.tessie-charge-setter :refer [new-TessieChargeSetter]]
    [tesla-solar-charger.gophers.process-sms-messages :refer [fetch-new-sms-messages]]
    [tesla-solar-charger.regulator.target-regulator :refer [new-TargetRegulator]]
    [tesla-solar-charger.recorder.csv-recorder :refer [new-CSVRecorder]]
    [tesla-solar-charger.gophers.get-car-state :refer [fetch-new-car-state]]
    [tesla-solar-charger.gophers.set-charge-rate :refer [set-charge-rate]]
    [tesla-solar-charger.gophers.record-data :refer [record-data]]
    [better-cond.core :refer [cond] :rename {cond better-cond}]
    [tesla-solar-charger.utils :as utils]
    [clj-http.client :as client]
    [duratom.core :refer [duratom]]
    [taoensso.timbre :as timbre]
    [clojure.java.io :refer [make-parents]]
    [taoensso.timbre.appenders.core :as appenders]
    [tesla-solar-charger.gophers.regulate-charge-rate :refer [regulate-charge-rate]]
    [tesla-solar-charger.gophers.utils :refer [sliding-buffer split-ch split-channel keep-last-value print-values] :rename {sliding-buffer my-sliding-buffer}]
    [tesla-solar-charger.car-data-source.tessie-data-source :refer [new-TessieDataSource]]
    [tesla-solar-charger.solar-data-source.gosungrow-data-source :refer [new-GoSungrowDataSource]]
    [tesla-solar-charger.gophers.get-solar-data :refer [fetch-new-solar-data]]
    [clojure.core.async :as async :refer [close! chan sliding-buffer]]))

(defn getenv
  [key]
  (let [value (System/getenv key)]
    (when (nil? value)
      (timbre/errorf "[Main] Enviromnent variable %s is not defined" key))
    value))

(defn ntfy
  [channel-name message]
  (let [url (format "https://ntfy.sh/%s" channel-name)]
    (try
      (client/post url {:body message})
      (catch Exception _)
      (catch clojure.lang.ExceptionInfo _))))

(defn -main
  [& args]
  (timbre/warn "[Main] Starting...")
  (let [settings-filepath (.getAbsolutePath (clojure.java.io/file (getenv "SETTINGS_FILEPATH")))
        settings (duratom :local-file
                          :file-path settings-filepath
                          :init {})
        kill-ch (chan)
        shutdown-hook (fn [] (timbre/warn "[Main] Sending kill signal...") (close! kill-ch))

        log-filename "logs.log"

        _ (timbre/merge-config!
          {:appenders {:spit (appenders/spit-appender {:fname log-filename})}})

        _ (try 
          (clojure.java.io/delete-file log-filename)
          (catch java.io.IOException _))

        ntfy-channel-name (getenv "NTFY_CHANNEL_NAME")

        timbre-ntfy (fn [data] 
                      (let [{:keys [vargs]} data
                            message (apply str vargs)]
                        (ntfy ntfy-channel-name message)))

        ntfy-appender {:min-level :warn
                       :enabled? true
                       :async? true
                       :fn timbre-ntfy}

        _ (timbre/merge-config!
            {:appenders {:ntfy ntfy-appender}})

        tesla-name (getenv "CAR_NAME")
        tesla-vin (getenv "TESLA_VIN")
        tessie-auth-token (getenv "TESSIE_AUTH_TOKEN")
        locationiq-auth-token (getenv "LOCATIONIQ_AUTH_TOKEN")
        car-data-source (new-TessieDataSource tesla-vin tessie-auth-token locationiq-auth-token)
        charge-setter (new-TessieChargeSetter tessie-auth-token tesla-vin)

        office-csv-filepath (getenv "CSV_FILEPATH")
        office-csv-recorder (new-CSVRecorder office-csv-filepath)

        home-csv-filepath (getenv "CSV_FILEPATH2")
        home-csv-recorder (new-CSVRecorder home-csv-filepath)

        gosungrow-filepath-office (getenv "GOSUNGROW_FILEPATH_OFFICE")
        gosungrow-filepath-home (getenv "GOSUNGROW_FILEPATH_HOME")
        gosungrow-appkey (getenv "GOSUNGROW_APPKEY")
        sungrow-username (getenv "SUNGROW_USERNAME")
        sungrow-password (getenv "SUNGROW_PASSWORD")

        office-ps-key (getenv "GOSUNGROW_PS_KEY")
        office-ps-id (getenv "GOSUNGROW_PS_ID")
        office-excess-power-key (getenv "GOSUNGROW_EXCESS_POWER_KEY")
        office-solar-data-source (new-GoSungrowDataSource 
                                   gosungrow-filepath-office
                                   gosungrow-appkey 
                                   sungrow-username 
                                   sungrow-password 
                                   office-ps-key 
                                   office-ps-id 
                                   office-excess-power-key)

        home-ps-key (getenv "GOSUNGROW_PS_KEY2")
        home-ps-id (getenv "GOSUNGROW_PS_ID2")
        home-excess-power-key (getenv "GOSUNGROW_EXCESS_POWER_KEY2")
        home-solar-data-source (new-GoSungrowDataSource 
                                 gosungrow-filepath-home
                                 gosungrow-appkey 
                                 sungrow-username 
                                 sungrow-password 
                                 home-ps-key 
                                 home-ps-id 
                                 home-excess-power-key)

        office-name (getenv "LOCATION_NAME")
        office-latitude (parse-double (getenv "LOCATION_LATITUDE"))
        office-longitude (parse-double (getenv "LOCATION_LONGITUDE"))
        office-location {:latitude office-latitude 
                         :longitude office-longitude 
                         :name office-name}

        home-name (getenv "LOCATION_NAME2")
        home-latitude (parse-double (getenv "LOCATION_LATITUDE2"))
        home-longitude (parse-double (getenv "LOCATION_LONGITUDE2"))
        home-location {:latitude home-latitude 
                       :longitude home-longitude 
                       :name home-name}
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

        office-regulator (new-TargetRegulator 
                           tesla-name 
                           office-location 
                           (format "%s Regulator" office-name))

        home-regulator (new-TargetRegulator 
                         tesla-name 
                         home-location 
                         (format "%s Regulator" home-name))]

    (.addShutdownHook (Runtime/getRuntime) (Thread. shutdown-hook))

    (split-ch tesla-state-ch tesla-state-ch2 tesla-state-ch3 tesla-state-ch4 tesla-state-ch5)
    (split-ch home-solar-data-ch home-solar-data-ch2 home-solar-data-ch3)
    (split-ch office-solar-data-ch office-solar-data-ch2 office-solar-data-ch3)

    (fetch-new-car-state car-data-source tesla-state-ch kill-ch "Tessie Data Source")

    (fetch-new-solar-data office-solar-data-source office-solar-data-ch kill-ch (format "%s Data Source" office-name))

    (fetch-new-solar-data home-solar-data-source home-solar-data-ch kill-ch (format "%s Data Source" home-name))

    (regulate-charge-rate office-regulator tesla-state-ch2 office-solar-data-ch2 charge-power-ch settings kill-ch (format "%s Regulator" office-name))

    (regulate-charge-rate home-regulator tesla-state-ch3 home-solar-data-ch2 charge-power-ch settings kill-ch (format "%s Regulator" home-name))

    (record-data office-csv-recorder office-location tesla-state-ch4 office-solar-data-ch3 kill-ch (format "%s Recorder" office-name))

    (record-data home-csv-recorder home-location tesla-state-ch5 home-solar-data-ch3 kill-ch (format "%s Recorder" home-name))

    (set-charge-rate charge-setter charge-power-ch kill-ch "Tessie Charge Setter")

    (while true)

    (timbre/shutdown-appenders!)
    (shutdown-agents)))

