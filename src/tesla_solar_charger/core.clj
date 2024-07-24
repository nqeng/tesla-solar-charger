(ns tesla-solar-charger.core
  (:gen-class)
  (:require
   [clojure.tools.cli :refer [parse-opts]]
   [tesla-solar-charger.log :as log]
   [tesla-solar-charger.gophers.get-car-state :refer [get-new-car-state]]
   [tesla-solar-charger.gophers.set-charge-rate :refer [set-charge-rate]]
   [better-cond.core :refer [cond] :rename {cond better-cond}]
   [tesla-solar-charger.charger.three-phase-tesla-charger :refer [new-TeslaChargerThreePhase]]
   [tesla-solar-charger.utils :as utils]
   [tesla-solar-charger.gophers.utils :refer [sliding-buffer keep-last-value print-values]]
   [tesla-solar-charger.car.tesla :refer [new-Tesla]]
   [tesla-solar-charger.data-source.gosungrow-data-source :refer [new-GoSungrowDataSource]]
   [tesla-solar-charger.gophers.get-site-data :refer [get-new-site-data]]
   [clojure.core.async :as async]))

(def cli-options
  ;; An option with a required argument
  [["-l" "--log-level" "Log level"
    :default log/default-log-level
    :parse-fn keyword
    :validate [#(contains? #{:info :verbose :error} %) "Must be one of: (info, verbose, error)"]]
   ["-h" "--help"]])

(defn make-charge-site
  [latitude longitude]
  {:latitude latitude
   :longitude longitude})

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
   :do (log/info "Starting...")

   (let [tesla-vin (System/getenv "TESLA_VIN")
         tessie-auth-token (System/getenv "TESSIE_AUTH_TOKEN")
         script-filepath (System/getenv "GOSUNGROW_SCRIPT_FILEPATH")
         ps-key (System/getenv "GOSUNGROW_PS_KEY")
         ps-id (System/getenv "GOSUNGROW_PS_ID")
         ps-point (System/getenv "GOSUNGROW_PS_POINT")
         error-ch (async/chan (async/dropping-buffer 1))
         kill-ch (async/chan)
         car (new-Tesla tesla-vin tessie-auth-token)
         data-source (new-GoSungrowDataSource script-filepath
                                              ps-key
                                              ps-id
                                              ps-point)
         charger (new-TeslaChargerThreePhase)
         site (make-charge-site 0 0)
         car-state-ch (get-new-car-state car error-ch kill-ch)
         site-data-ch (get-new-site-data data-source error-ch kill-ch)
         power-watts-ch (set-charge-rate charger car error-ch kill-ch)]

     (.addShutdownHook
      (Runtime/getRuntime)
      (Thread.
       (fn []
         (println "Sending kill signal...")
         (async/>!! kill-ch true))))

     (when-some [error (async/<!! error-ch)]
       (let [stack-trace-string (with-out-str (clojure.stacktrace/print-stack-trace error))]
         (log/error stack-trace-string)
         (log/notify stack-trace-string))))))

