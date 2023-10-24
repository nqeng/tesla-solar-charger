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

   (let [dummy-tesla (dummy-tesla/->DummyTesla "1234")
         tesla (tesla/->Tesla "vin" "api-key")
         work-site (sungrow-site/->SungrowSite "site1" "Work" 0 0 "username" "password" {:excess-power-watts ["ps-id" "ps-key"]} sungrow-site/power-to-current-3-phase)
         home-site (sungrow-site/->SungrowSite "site2" "Home" 0 0 "username" "password" {:excess-power-watts ["ps-id" "ps-key"]} sungrow-site/power-to-current-2-phase)
         solar-sites [work-site home-site]
         get-settings-chan (async/chan)
         set-settings-chan (async/chan)
         tesla-new-state-chan (async/chan (async/sliding-buffer 1))
         tesla-current-state-chan (async/chan)
         tesla-new-state-chan-split1 (async/chan (async/sliding-buffer 1))
         tesla-new-state-chan-split2 (async/chan (async/sliding-buffer 1))
         tesla-new-state-chan-split3 (async/chan (async/sliding-buffer 1))
         work-new-data-chan (async/chan (async/sliding-buffer 1))
         work-current-data-chan (async/chan)
         home-new-data-chan (async/chan (async/sliding-buffer 1))
         home-current-data-chan (async/chan)
         new-sms-chan (async/chan 5)
         error-chan (async/chan (async/dropping-buffer 1))
         log-chan (async/chan (async/sliding-buffer 10))
         all-channels [get-settings-chan
                       set-settings-chan
                       tesla-new-state-chan
                       tesla-current-state-chan
                       tesla-new-state-chan-split1
                       tesla-new-state-chan-split2
                       tesla-new-state-chan-split3
                       home-new-data-chan
                       home-current-data-chan
                       work-new-data-chan
                       work-current-data-chan
                       new-sms-chan
                       error-chan
                       log-chan]]

     (log-loop log-level log-chan error-chan)

     (process-sms-messages
      "SMS"
      "username"
      "api-key"
      [(sms-processors/->SetTargetPercent set-settings-chan get-settings-chan tesla tesla-current-state-chan solar-sites)
       (sms-processors/->SetPowerBuffer set-settings-chan get-settings-chan tesla tesla-current-state-chan solar-sites)
       (sms-processors/->SetTargetTime set-settings-chan get-settings-chan tesla tesla-current-state-chan solar-sites)]
      error-chan
      log-chan)

     (provide-settings
      "Settings"
      "settings.json"
      get-settings-chan
      set-settings-chan
      error-chan
      log-chan)

     (get-car-state
      "State (Tesla)"
      tesla
      tesla-new-state-chan
      error-chan
      log-chan)

     (split-channel
      tesla-new-state-chan
      [tesla-new-state-chan-split1 tesla-new-state-chan-split2 tesla-new-state-chan-split3]
      error-chan
      log-chan)

     (provide-current-channel-value
      tesla-new-state-chan-split3
      tesla-current-state-chan
      error-chan
      log-chan)

     (get-site-data
      "Data (Work)"
      work-site
      work-new-data-chan
      error-chan
      log-chan)

     (provide-current-channel-value
      work-new-data-chan
      work-current-data-chan
      error-chan
      log-chan)

     (get-site-data
      "Data (Home)"
      home-site
      home-new-data-chan
      error-chan
      log-chan)

     (provide-current-channel-value
      home-new-data-chan
      home-current-data-chan
      error-chan
      log-chan)

     (regulate-charge-rate
      "Regulator (Tesla + Work)"
      (default-regulator/->DefaultRegulator tesla work-site (target-time-regulation-creater/->TargetTimeRegulationCreater get-settings-chan))
      tesla-new-state-chan-split1
      work-current-data-chan
      error-chan
      log-chan)

     (regulate-charge-rate
      "Regulator (Tesla + Home)"
      (default-regulator/->DefaultRegulator tesla home-site (simple-regulation-creater/->SimpleRegulationCreater get-settings-chan))
      tesla-new-state-chan-split2
      home-current-data-chan
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

