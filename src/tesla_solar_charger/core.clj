(ns tesla-solar-charger.core
  (:gen-class)
  (:require
   [clojure.tools.cli :refer [parse-opts]]
   [tesla-solar-charger.log :as log]
   [tesla-solar-charger.gophers.get-car-state :refer [filter-new-car-state fetch-car-state get-new-car-state]]
   [tesla-solar-charger.gophers.set-charge-rate :refer [set-charge-rate set-override]]
   [better-cond.core :refer [cond] :rename {cond better-cond}]
   [tesla-solar-charger.charger.three-phase-tesla-charger :refer [new-TeslaChargerThreePhase]]
   [tesla-solar-charger.utils :as utils]
   [tesla-solar-charger.gophers.regulate-charge-rate :refer [regulate-charge-rate]]
   [tesla-solar-charger.gophers.utils :refer [sliding-buffer keep-last-value print-values] :rename {sliding-buffer my-sliding-buffer}]
   [tesla-solar-charger.car.tesla :refer [new-Tesla]]
   [tesla-solar-charger.data-source.gosungrow-data-source :refer [new-GoSungrowDataSource]]
   [tesla-solar-charger.gophers.get-site-data :refer [filter-new-solar-data fetch-solar-data get-new-site-data]]
   [clojure.core.async :as async :refer [close! chan sliding-buffer]]))

(def cli-options
  ;; An option with a required argument
  [["-l" "--log-level" "Log level"
    :default log/default-log-level
    :parse-fn keyword
    :validate [#(contains? #{:info :verbose :error} %) "Must be one of: (info, verbose, error)"]]
   ["-h" "--help"]])

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
   :do (log/info "[Main]" "Starting...")

   (let [tesla-vin (System/getenv "TESLA_VIN")
         tessie-auth-token (System/getenv "TESSIE_AUTH_TOKEN")
         script-filepath (System/getenv "GOSUNGROW_SCRIPT_FILEPATH")
         ps-key (System/getenv "GOSUNGROW_PS_KEY")
         ps-id (System/getenv "GOSUNGROW_PS_ID")
         ps-point (System/getenv "GOSUNGROW_PS_POINT")
         location-latitude (parse-double (System/getenv "LOCATION_LATITUDE"))
         location-longitude (parse-double (System/getenv "LOCATION_LONGITUDE"))
         locationiq-auth-token (System/getenv "LOCATIONIQ_AUTH_TOKEN")
         kill-ch (async/chan)
         car (new-Tesla tesla-vin tessie-auth-token locationiq-auth-token)
         data-source (new-GoSungrowDataSource script-filepath
                                              ps-key
                                              ps-id
                                              ps-point)
         charger (new-TeslaChargerThreePhase)
         location {:latitude location-latitude :longitude location-longitude}
         car-state-ch (fetch-car-state car kill-ch)
         new-car-state-ch (filter-new-car-state car car-state-ch kill-ch)
         data-point-ch (fetch-solar-data data-source kill-ch)
         new-data-point-ch (filter-new-solar-data data-point-ch kill-ch)
         _ (my-sliding-buffer new-data-point-ch 1)
         _ (my-sliding-buffer new-car-state-ch 1)
         charge-power-ch (chan (sliding-buffer 1))
         override-ch (chan (sliding-buffer 1))
         target-percent 80
         target-time (utils/time-now)
         _ (regulate-charge-rate location charger new-car-state-ch new-data-point-ch charge-power-ch override-ch kill-ch target-percent target-time)
         _ (set-override car override-ch kill-ch)
         _ (set-charge-rate car charger charge-power-ch kill-ch)]

     (Thread/sleep 60000)
     (log/info "[Main]" "Sending kill signal...")
     (close! kill-ch)

     #_(.addShutdownHook
        (Runtime/getRuntime)
        (Thread.
         (fn []
           (println "Sending kill signal...")
           (close! kill-ch))))

     #_(when-some [error (async/<!! err-ch)]
         (let [stack-trace-string (with-out-str (clojure.stacktrace/print-stack-trace error))]
           (log/error stack-trace-string)
           (log/notify stack-trace-string))))))

