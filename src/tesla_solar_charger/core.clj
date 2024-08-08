(ns tesla-solar-charger.core
  (:gen-class)
  (:require
    [clojure.tools.cli :refer [parse-opts]]
    [tesla-solar-charger.car-charge-setter.tessie-charge-setter :refer [new-TessieChargeSetter]]
    [tesla-solar-charger.gophers.process-sms-messages :refer [fetch-new-sms-messages]]
    [tesla-solar-charger.regulator.target-regulator :refer [new-TargetRegulator]]
    [tesla-solar-charger.gophers.get-car-state :refer [fetch-new-car-state]]
    [tesla-solar-charger.gophers.set-charge-rate :refer [set-charge-rate]]
    [better-cond.core :refer [cond] :rename {cond better-cond}]
    [tesla-solar-charger.utils :as utils]
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

(def log-filename "logs.log")

(timbre/merge-config!
  {:appenders {:spit (appenders/spit-appender {:fname log-filename})}})

(try 
  (clojure.java.io/delete-file log-filename)
  (catch java.io.IOException _))

(defn getenv
  [key]
  (let [value (System/getenv key)]
    (when (nil? value)
      (throw (ex-info (format "Enviromnent variable %s is not defined" key) {})))
    value))

(def settings-filepath (.getAbsolutePath (clojure.java.io/file (getenv "SETTINGS_FILEPATH"))))

(defn send-to-ntfy
  [channel-name message]
  (try
    (client/post (format "https://ntfy.sh/%s" channel-name) message)
    (catch Exception _)
    (catch clojure.lang.ExceptionInfo _)))

(def settings (duratom :local-file
                       :file-path settings-filepath
                       :init {}))

(defn -main
  [& args]
  (timbre/info "Starting...")
  (let [kill-ch (chan)
        shutdown-hook (fn [] (timbre/info "Sending kill signal...") (close! kill-ch))
        tesla-vin (getenv "TESLA_VIN")
        tessie-auth-token (getenv "TESSIE_AUTH_TOKEN")
        script-filepath (getenv "GOSUNGROW_SCRIPT_FILEPATH")
        gosungrow-appkey (getenv "GOSUNGROW_APPKEY")
        sungrow-username (getenv "SUNGROW_USERNAME")
        sungrow-password (getenv "SUNGROW_PASSWORD")
        ps-key (getenv "GOSUNGROW_PS_KEY")
        ps-id (getenv "GOSUNGROW_PS_ID")
        excess-power-key (getenv "GOSUNGROW_EXCESS_POWER_KEY")
        location-latitude (parse-double (getenv "LOCATION_LATITUDE"))
        location-longitude (parse-double (getenv "LOCATION_LONGITUDE"))
        ps-key2 (getenv "GOSUNGROW_PS_KEY2")
        ps-id2 (getenv "GOSUNGROW_PS_ID2")
        excess-power-key2 (getenv "GOSUNGROW_EXCESS_POWER_KEY2")
        location-latitude2 (parse-double (getenv "LOCATION_LATITUDE2"))
        location-longitude2 (parse-double (getenv "LOCATION_LONGITUDE2"))
        locationiq-auth-token (getenv "LOCATIONIQ_AUTH_TOKEN")
        car-name (getenv "CAR_NAME")
        car-data-source (new-TessieDataSource tesla-vin tessie-auth-token locationiq-auth-token)
        solar-data-source (new-GoSungrowDataSource script-filepath gosungrow-appkey sungrow-username sungrow-password ps-key ps-id excess-power-key)
        solar-data-source2 (new-GoSungrowDataSource script-filepath gosungrow-appkey sungrow-username sungrow-password ps-key2 ps-id2 excess-power-key2)
        charge-setter (new-TessieChargeSetter tessie-auth-token tesla-vin)
        location-name (getenv "LOCATION_NAME")
        location {:latitude location-latitude :longitude location-longitude :name location-name}
        location-name2 (getenv "LOCATION_NAME2")
        location2 {:latitude location-latitude2 :longitude location-longitude2 :name location-name2}
        regulator (new-TargetRegulator car-name location (partial deref settings))
        regulator2 (new-TargetRegulator car-name location2 (partial deref settings))
        car-state-ch (chan)
        car-state-ch2 (chan)
        car-state-ch3 (chan)
        solar-data-ch (chan)
        solar-data-ch2 (chan)
        charge-power-ch (chan (sliding-buffer 1))]

    (.addShutdownHook (Runtime/getRuntime) (Thread. shutdown-hook))

    (split-ch car-state-ch car-state-ch2 car-state-ch3)

    (fetch-new-car-state car-data-source car-state-ch kill-ch "Tessie Data Source")

    (fetch-new-solar-data solar-data-source solar-data-ch kill-ch (format "%s Data Source" location-name))

    (fetch-new-solar-data solar-data-source2 solar-data-ch2 kill-ch (format "%s Data Source" location-name2))

    (regulate-charge-rate regulator car-state-ch2 solar-data-ch charge-power-ch kill-ch (format "%s Regulator" location-name))

    (regulate-charge-rate regulator2 car-state-ch3 solar-data-ch2 charge-power-ch kill-ch (format "%s Regulator" location-name2))

    (set-charge-rate charge-setter charge-power-ch kill-ch "Tessie Charge Setter")

    (while true)

    (timbre/shutdown-appenders!)
    (shutdown-agents)))

