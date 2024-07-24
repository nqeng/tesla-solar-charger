(ns tesla-solar-charger.core
  (:gen-class)
  (:require
   [clojure.tools.cli :refer [parse-opts]]
   [tesla-solar-charger.log :as log]
   [tesla-solar-charger.gophers.get-car-state :refer [get-new-car-state]]
   [better-cond.core :refer [cond] :rename {cond better-cond}]
   [tesla-solar-charger.utils :as utils]
   [tesla-solar-charger.gophers.utils :refer [sliding-buffer keep-last-value print-values]]
   [tesla-solar-charger.implementations.car.tesla :refer [new-Tesla]]
   [tesla-solar-charger.implementations.site.sungrow-site :refer [new-SungrowSite]]
   [tesla-solar-charger.implementations.site-data.sungrow-gosungrow-data-source :refer [new-SungrowGoSungrowDataSource]]
   [tesla-solar-charger.implementations.site-data.sungrow-live-data-source :refer [new-SungrowLiveDataSource]]
   [tesla-solar-charger.gophers.get-site-data :refer [get-new-site-data]]
   [clojure.core.async :as async]
   [tesla-solar-charger.interfaces.site :as Isite]))

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
         car1 (new-Tesla tesla-vin tessie-auth-token)
         solar-data-source (new-SungrowGoSungrowDataSource
                            script-filepath
                            ps-key
                            ps-id
                            ps-point)
         site1 (make-charge-site 0 0)
         car-state-ch (get-new-car-state car1 error-ch kill-ch)
         site-data-ch (get-new-site-data solar-data-source error-ch kill-ch)]

     #_(log-loop log-level log-chan error-chan)

     #_(process-sms-messages
        "Process SMS"
        [(sms-processors/->SetTargetPercent set-settings-chan get-settings-chan car1 current-car1-state-chan solar-sites)
         (sms-processors/->SetPowerBuffer set-settings-chan get-settings-chan car1 current-car1-state-chan solar-sites)
         (sms-processors/->SetTargetTime set-settings-chan get-settings-chan car1 current-car1-state-chan solar-sites)]
        error-chan
        log-chan)

     #_(provide-settings
        ""
        ""
        get-settings-chan
        set-settings-chan
        error-ch
        log-ch)

     #_(split-channel
        new-car1-state-chan
        [new-car1-state-chan-split1 new-car1-state-chan-split2 new-car1-state-chan-split3]
        error-chan
        log-chan)

     #_(provide-current-channel-value
        new-car1-state-chan-split3
        current-car1-state-chan
        error-chan
        log-chan)

     #_(get-site-data
        ""
        (first solar-sites)
        new-site1-data-chan
        error-chan
        log-chan)

     #_(provide-current-channel-value
        new-site1-data-chan
        current-site1-data-chan
        error-chan
        log-chan)

     #_(regulate-charge-rate
        ""
        (-> (default-regulator/->DefaultRegulator car1 (second solar-sites))
            (regulator/with-regulation-creater (target-time-regulation-creater/->TargetTimeRegulationCreater get-settings-chan)))
        new-car1-state-chan-split1
        current-site2-data-chan
        error-chan
        log-chan)

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

