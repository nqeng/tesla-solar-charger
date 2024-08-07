(ns tesla-solar-charger.core
  (:gen-class)
  (:require
   [clojure.tools.cli :refer [parse-opts]]
   [tesla-solar-charger.car-charge-setter.tessie-charge-setter :refer [new-TessieChargeSetter]]
   [tesla-solar-charger.gophers.process-sms-messages :refer [fetch-new-sms-messages]]
   [tesla-solar-charger.log :as log]
   [tesla-solar-charger.regulator.target-regulator :refer [new-TargetRegulator]]
   [tesla-solar-charger.gophers.get-car-state :refer [fetch-new-car-state]]
   [tesla-solar-charger.gophers.set-charge-rate :refer [set-charge-rate]]
   [better-cond.core :refer [cond] :rename {cond better-cond}]
   [tesla-solar-charger.utils :as utils]
   [duratom.core :refer [duratom]]
   [tesla-solar-charger.gophers.regulate-charge-rate :refer [regulate-charge-rate]]
   [tesla-solar-charger.gophers.utils :refer [sliding-buffer split-ch split-channel keep-last-value print-values] :rename {sliding-buffer my-sliding-buffer}]
   [tesla-solar-charger.car-data-source.tessie-data-source :refer [new-TessieDataSource]]
   [tesla-solar-charger.solar-data-source.gosungrow-data-source :refer [new-GoSungrowDataSource]]
   [tesla-solar-charger.gophers.get-solar-data :refer [fetch-new-solar-data]]
   [clojure.core.async :as async :refer [close! chan sliding-buffer]]))

(def cli-options
  ;; An option with a required argument
  [["-l" "--log-level" "Log level"
    :default log/default-log-level
    :parse-fn keyword
    :validate [#(contains? #{:info :verbose :error} %) "Must be one of: (info, verbose, error)"]]
   ["-h" "--help"]])

(defn get-env-or-throw
  [key]
  (let [value (System/getenv key)]
    (if (some? value)
      value
      (throw (ex-info (format "enviromnent variable %s is not defined" key) {})))))

(def settings (duratom :local-file
                       :file-path (.getAbsolutePath (clojure.java.io/file (get-env-or-throw "SETTINGS_FILEPATH")))
                       :init {}))

(defn -main
  [& args]
  (better-cond

   :let [options (parse-opts args cli-options)
         errors (:errors options)]

   (some? errors)
   (doseq [error errors]
     (println error))

   :let [log-level (-> options
                       :options
                       :log-level)]

   :do (log/set-log-level log-level)
   :do (log/info "Main" "Starting...")

   (let [tesla-vin (get-env-or-throw "TESLA_VIN")
         tessie-auth-token (get-env-or-throw "TESSIE_AUTH_TOKEN")
         script-filepath (get-env-or-throw "GOSUNGROW_SCRIPT_FILEPATH")
         gosungrow-appkey (get-env-or-throw "GOSUNGROW_APPKEY")
         sungrow-username (get-env-or-throw "SUNGROW_USERNAME")
         sungrow-password (get-env-or-throw "SUNGROW_PASSWORD")
         ps-key (get-env-or-throw "GOSUNGROW_PS_KEY")
         ps-id (get-env-or-throw "GOSUNGROW_PS_ID")
         excess-power-key (get-env-or-throw "GOSUNGROW_EXCESS_POWER_KEY")
         location-latitude (parse-double (get-env-or-throw "LOCATION_LATITUDE"))
         location-longitude (parse-double (get-env-or-throw "LOCATION_LONGITUDE"))
         ps-key2 (get-env-or-throw "GOSUNGROW_PS_KEY2")
         ps-id2 (get-env-or-throw "GOSUNGROW_PS_ID2")
         excess-power-key2 (get-env-or-throw "GOSUNGROW_EXCESS_POWER_KEY2")
         location-latitude2 (parse-double (get-env-or-throw "LOCATION_LATITUDE2"))
         location-longitude2 (parse-double (get-env-or-throw "LOCATION_LONGITUDE2"))
         locationiq-auth-token (get-env-or-throw "LOCATIONIQ_AUTH_TOKEN")
         car-name (get-env-or-throw "CAR_NAME")
         kill-ch (chan)
         car-data-source (new-TessieDataSource tesla-vin tessie-auth-token locationiq-auth-token)
         solar-data-source (new-GoSungrowDataSource script-filepath gosungrow-appkey sungrow-username sungrow-password ps-key ps-id excess-power-key)
         solar-data-source2 (new-GoSungrowDataSource script-filepath gosungrow-appkey sungrow-username sungrow-password ps-key2 ps-id2 excess-power-key2)
         charge-setter (new-TessieChargeSetter tessie-auth-token tesla-vin)
         location-name (get-env-or-throw "LOCATION_NAME")
         location {:latitude location-latitude :longitude location-longitude :name location-name}
         location-name2 (get-env-or-throw "LOCATION_NAME2")
         location2 {:latitude location-latitude2 :longitude location-longitude2 :name location-name2}
         regulator (new-TargetRegulator car-name location (partial deref settings))
         regulator2 (new-TargetRegulator car-name location2 (partial deref settings))
         car-state-ch (chan)
         car-state-ch2 (chan)
         car-state-ch3 (chan)
         solar-data-ch (chan)
         solar-data-ch2 (chan)
         charge-power-ch (chan (sliding-buffer 1))]

     (split-ch car-state-ch car-state-ch2 car-state-ch3)

     (fetch-new-car-state car-data-source car-state-ch kill-ch "Tessie Data Source")

     (fetch-new-solar-data solar-data-source solar-data-ch kill-ch (format "%s Data Source" location-name))

     (fetch-new-solar-data solar-data-source2 solar-data-ch2 kill-ch (format "%s Data Source" location-name2))

     (regulate-charge-rate regulator car-state-ch2 solar-data-ch charge-power-ch kill-ch (format "%s Regulator" location-name))

     (regulate-charge-rate regulator2 car-state-ch3 solar-data-ch2 charge-power-ch kill-ch (format "%s Regulator" location-name2))

     (set-charge-rate charge-setter charge-power-ch kill-ch)

     ;(Thread/sleep 60000)
     ;(log/info "Main" "Sending kill signal...")
     ;(close! kill-ch)

     (while true)

     (.addShutdownHook (Runtime/getRuntime) (Thread. (partial close! kill-ch))))))

