(ns tesla-solar-charger.core
  (:gen-class)
  (:require
   [clojure.tools.cli :refer [parse-opts]]
   [tesla-solar-charger.gophers.get-car-state :refer [get-car-state]]
   [better-cond.core :refer [cond] :rename {cond better-cond}]
   [tesla-solar-charger.utils :as utils]
   [tesla-solar-charger.gophers.get-site-data :refer [get-site-data]]
   [tesla-solar-charger.gophers.regulate-charge-rate :refer [regulate-charge-rate]]
   [tesla-solar-charger.implementations.sms-processors :as sms-processors]
   [tesla-solar-charger.gophers.provide-settings :refer [provide-settings]]
   [tesla-solar-charger.gophers.provide-current-channel-value :refer [provide-current-channel-value]]
   [tesla-solar-charger.gophers.split-channel :refer [split-channel]]
   [tesla-solar-charger.gophers.process-sms-messages :refer [process-sms-messages]]
   [tesla-solar-charger.gophers.logger :refer [log-loop log]]
   [tesla-solar-charger.implementations.dummy-tesla :as dummy-tesla]
   [tesla-solar-charger.implementations.tesla :as tesla]
   [tesla-solar-charger.implementations.sungrow-site :as sungrow-site]
   [tesla-solar-charger.implementations.dummy-site :as dummy-site]
   [tesla-solar-charger.interfaces.regulator :as regulator]
   [tesla-solar-charger.implementations.simple-regulation-creater :as simple-regulation-creater]
   [tesla-solar-charger.implementations.target-time-regulation-creater :as target-time-regulation-creater]
   [tesla-solar-charger.implementations.default-regulator :as default-regulator]
   [clojure.core.async :as async]
   [tesla-solar-charger.interfaces.site :as site]
   [tesla-solar-charger.interfaces.car :as car]))

(def solar-sites
  [(sungrow-site/->SungrowSite
    "site1"
    ""
    0
    0
    ""
    ""
    ""
    5
    {})
   (sungrow-site/->SungrowSite
    "site2"
    ""
    0
    0
    ""
    ""
    ""
    5
    {})])

(def cli-options
  ;; An option with a required argument
  [["-l" "--log-level" "Log level"
    :default "info"
    :validate [#(contains? #{"info" "verbose" "error"} %) "Must be one of: (info, verbose, error)"]]
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
                       :log-level
                       keyword)]

   :do (log log-level :info nil "Starting...")

   (let [car1 (dummy-tesla/->DummyTesla "1234")
         car1 (tesla/->Tesla "" "")
         get-settings-chan (async/chan)
         set-settings-chan (async/chan)
         new-car1-state-chan (async/chan (async/sliding-buffer 1))
         current-car1-state-chan (async/chan)
         new-car1-state-chan-split1 (async/chan (async/sliding-buffer 1))
         new-car1-state-chan-split2 (async/chan (async/sliding-buffer 1))
         new-car1-state-chan-split3 (async/chan (async/sliding-buffer 1))
         new-site1-data-chan (async/chan (async/sliding-buffer 1))
         current-site1-data-chan (async/chan)
         new-site2-data-chan (async/chan (async/sliding-buffer 1))
         current-site2-data-chan (async/chan)
         sms-chan (async/chan 5)
         error-chan (async/chan (async/dropping-buffer 1))
         log-chan (async/chan (async/sliding-buffer 10))
         all-channels [get-settings-chan
                       set-settings-chan
                       new-car1-state-chan
                       new-car1-state-chan-split1
                       new-car1-state-chan-split2
                       new-car1-state-chan-split3
                       new-site2-data-chan
                       current-site2-data-chan
                       current-car1-state-chan
                       new-site1-data-chan
                       current-site1-data-chan
                       sms-chan
                       error-chan
                       log-chan]]

     (log-loop log-level log-chan error-chan)

     (process-sms-messages
      ""
      ""
      ""
      [(sms-processors/->SetTargetPercent set-settings-chan get-settings-chan car1 current-car1-state-chan solar-sites)
       (sms-processors/->SetPowerBuffer set-settings-chan get-settings-chan car1 current-car1-state-chan solar-sites)
       (sms-processors/->SetTargetTime set-settings-chan get-settings-chan car1 current-car1-state-chan solar-sites)]
      error-chan
      log-chan)

     (provide-settings
      ""
      ""
      get-settings-chan
      set-settings-chan
      error-chan
      log-chan)

     (get-car-state
      ""
      car1
      new-car1-state-chan
      error-chan
      log-chan)

     (split-channel
      new-car1-state-chan
      [new-car1-state-chan-split1 new-car1-state-chan-split2 new-car1-state-chan-split3]
      error-chan
      log-chan)

     (provide-current-channel-value
      new-car1-state-chan-split3
      current-car1-state-chan
      error-chan
      log-chan)

     (get-site-data
        ""
        (second solar-sites)
        new-site2-data-chan
        error-chan
        log-chan)

     (provide-current-channel-value
        new-site2-data-chan
        current-site2-data-chan
        error-chan
        log-chan)

     (regulate-charge-rate
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
         (doseq [chan all-channels]
           (async/close! chan)))))

     (let [e (async/<!! error-chan)]
       (when (not (nil? e))
         (let [stack-trace-string (with-out-str (clojure.stacktrace/print-stack-trace e))]
           (log log-level :error nil stack-trace-string)
           (utils/send-to-ntfy stack-trace-string)))))))

