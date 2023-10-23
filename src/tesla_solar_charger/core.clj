(ns tesla-solar-charger.core
  (:gen-class)
  (:require
   [tesla-solar-charger.gophers.get-car-state :refer [get-car-state]]
   [tesla-solar-charger.gophers.get-site-data :refer [get-site-data]]
   [tesla-solar-charger.gophers.regulate-charge-rate :refer [regulate-charge-rate]]
   [tesla-solar-charger.gophers.provide-settings :refer [provide-settings]]
   [tesla-solar-charger.gophers.provide-current-channel-value :refer [provide-current-channel-value]]
   [tesla-solar-charger.gophers.split-channel :refer [split-channel]]
   [tesla-solar-charger.gophers.process-sms-messages :refer [process-sms-messages]]
   [tesla-solar-charger.gophers.logger :refer [log-loop log]]
   [tesla-solar-charger.implementations.dummy-tesla :as dummy-tesla]
   [tesla-solar-charger.implementations.sungrow-site :as sungrow-site]
   [tesla-solar-charger.implementations.dummy-site :as dummy-site]
   [tesla-solar-charger.interfaces.regulator :as regulator]
   [tesla-solar-charger.implementations.simple-regulation-creater :as simple-regulation-creater]
   [tesla-solar-charger.implementations.target-time-regulation-creater :as target-time-regulation-creater]
   [tesla-solar-charger.implementations.default-regulator :as default-regulator]
   [clojure.core.async :as async]))

(def solar-sites
  [(sungrow-site/->SungrowSite
     ""
     0
     0
     ""
     ""
     ""
    5
    {})
   (sungrow-site/->SungrowSite
     ""
     0
     0
     ""
     ""
     ""
     0
    {})])

(defn -main
  [& args]
  (println "Starting...")
  (let [car (dummy-tesla/->DummyTesla "1234")
        get-settings-chan (async/chan)
        set-settings-chan (async/chan)
        new-car-state-chan (async/chan (async/sliding-buffer 1))
        current-car-state-chan (async/chan)
        new-car-state-chan-split1 (async/chan (async/sliding-buffer 1))
        new-car-state-chan-split2 (async/chan (async/sliding-buffer 1))
        new-car-state-chan-split3 (async/chan (async/sliding-buffer 1))
        new-site1-data-chan (async/chan (async/sliding-buffer 1))
        current-site1-data-chan (async/chan)
        new-site2-data-chan (async/chan (async/sliding-buffer 1))
        current-site2-data-chan (async/chan)
        sms-chan (async/chan 5)
        error-chan (async/chan (async/dropping-buffer 1))
        log-chan (async/chan (async/sliding-buffer 10))
        all-channels [get-settings-chan
                      set-settings-chan
                      new-car-state-chan
                      new-car-state-chan-split1
                      new-car-state-chan-split2
                      new-site1-data-chan
                      current-site1-data-chan
                      sms-chan
                      error-chan
                      log-chan]]

    #_(process-sms-messages
       "Receive SMS"
       ""
       ""
       set-settings-chan
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
      ""
     car
     new-car-state-chan
     error-chan
     log-chan)

    (split-channel
     new-car-state-chan
     [new-car-state-chan-split1 new-car-state-chan-split2 new-car-state-chan-split3]
     error-chan
     log-chan)

    (provide-current-channel-value
     new-car-state-chan-split3
     current-car-state-chan
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
     (-> (default-regulator/->DefaultRegulator car (second solar-sites))
         (regulator/with-regulation-creater (target-time-regulation-creater/->TargetTimeRegulationCreater get-settings-chan)))
     new-car-state-chan-split1
     current-site2-data-chan
     error-chan
     log-chan)

    (log-loop :info log-chan error-chan)

    (.addShutdownHook
     (Runtime/getRuntime)
     (Thread.
      (fn []
        (log :info nil "Closing channels...")
        (doseq [chan all-channels]
          (async/close! chan))
        (log :info nil "Channels closed."))))

    (let [e (async/<!! error-chan)]
      (when (not (nil? e))
        (let [stack-trace-string (with-out-str (clojure.stacktrace/print-stack-trace e))]
          (async/>!! log-chan {:level :error :message stack-trace-string})
          #_(time-utils/send-to-ntfy stack-trace-string))))))

