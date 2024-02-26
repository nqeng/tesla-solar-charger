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

   (let [error-ch (async/chan (async/dropping-buffer 1))
           kill-ch (async/chan)
           car1 (new-Tesla "LRW3F7EKXPC780478" "P85JQZRL97qQ4KO6jVfODJnrIoSYUKtU")
           site-data-source1 (new-SungrowLiveDataSource
                              :firefox
                              {:headless true}
                              "reuben@nqeng.com.au"
                              "sungrownqe123"
                              "North Queensland Engineering")
           site1-location {:lat 0 :lng 0}
           site1 (-> (new-SungrowSite "nqe" "NQE Office" site1-location 50)
                     (Isite/with-data-source site-data-source1))
           new-car-state (get-new-car-state "" car1 error-ch kill-ch)
           new-site-data 0 #_(get-new-site-data "" site1 error-ch kill-ch)]

       (print-values "Received %s" new-car-state)
;     (print-values "Receieved %s" new-site-data)

       #_(log-loop log-level log-chan error-chan)

       #_(process-sms-messages
          "Process SMS"
          "caleb@nqeng.com.au"
          "C4E6DC42-D5C4-5082-F79B-88D3D7E2FDCD"
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

       #_(.addShutdownHook
          (Runtime/getRuntime)
          (Thread.
           (fn []
             (doseq [chan []]
               (async/close! chan)))))

       (when-some [error (async/<!! error-ch)]
         (let [stack-trace-string (with-out-str (clojure.stacktrace/print-stack-trace error))]
           (log/error stack-trace-string)
           (log/notify stack-trace-string))))))

