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
   [tesla-solar-charger.gophers.utils :refer [sliding-buffer keep-last-value print-values] :rename {sliding-buffer my-sliding-buffer}]
   [tesla-solar-charger.car-data-source.tessie-data-source :refer [new-TessieDataSource]]
   [tesla-solar-charger.data-source.gosungrow-data-source :refer [new-GoSungrowDataSource]]
   [tesla-solar-charger.gophers.get-site-data :refer [fetch-new-solar-data]]
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
         locationiq-auth-token (get-env-or-throw "LOCATIONIQ_AUTH_TOKEN")
         kill-ch (chan)
         car-data-source (new-TessieDataSource tesla-vin tessie-auth-token locationiq-auth-token)
         solar-data-source (new-GoSungrowDataSource script-filepath gosungrow-appkey sungrow-username sungrow-password ps-key ps-id excess-power-key)
         charge-setter (new-TessieChargeSetter tessie-auth-token tesla-vin)
         regulator (new-TargetRegulator (partial deref settings))
         location {:latitude location-latitude :longitude location-longitude}
         car-state-ch (chan)
         solar-data-ch (chan)
         charge-power-ch (chan (sliding-buffer 1))]

     (fetch-new-car-state car-data-source car-state-ch kill-ch)

     (fetch-new-solar-data solar-data-source solar-data-ch kill-ch)

     (regulate-charge-rate regulator location car-state-ch solar-data-ch charge-power-ch kill-ch)

     (set-charge-rate charge-setter charge-power-ch kill-ch)

     ;(Thread/sleep 60000)
     ;(log/info "Main" "Sending kill signal...")
     ;(close! kill-ch)

     (while true)

     (.addShutdownHook (Runtime/getRuntime) (Thread. (partial close! kill-ch))))))

