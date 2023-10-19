(ns tesla-solar-charger.core
  (:gen-class)
  (:require
   [clj-http.client :as client]
   [tesla-solar-charger.gophers.provide-car-state :refer [provide-new-car-state provide-current-car-state]]
   [tesla-solar-charger.gophers.provide-site-data :refer [provide-new-site-data provide-current-site-data]]
   [tesla-solar-charger.gophers.regulate-charge-rate :refer [regulate-car-charge-rate]]
   [tesla-solar-charger.gophers.provide-settings :refer [provide-settings]]
   [tesla-solar-charger.gophers.process-sms-messages :refer [process-sms-messages]]
   [tesla-solar-charger.gophers.logger :refer [log-loop log]]
   [cheshire.core :as json]
   [tesla-solar-charger.implementations.dummy-tesla :as dummy-tesla]
   [tesla-solar-charger.implementations.tesla :as tesla]
   [tesla-solar-charger.implementations.sungrow-site :as sungrow-site]
   [tesla-solar-charger.interfaces.regulator :as regulator]
   [tesla-solar-charger.implementations.simple-regulation-creater :as simple-regulation-creater]
   [tesla-solar-charger.implementations.target-time-regulation-creater :as target-time-regulation-creater]
   [tesla-solar-charger.implementations.default-regulator :as default-regulator]
   [tesla-solar-charger.time-utils :as time-utils]
   [clojure.core.async :as async]))

(def solar-sites
  [(sungrow-site/->SungrowSite
     ""
     0
     0
     ""
     ""
    {})
   (sungrow-site/->SungrowSite
     ""
     0
     0
     ""
     ""
    {})])

(defn -main
  [& args]
  (println "Starting...")
  (let [car (dummy-tesla/->DummyTesla "1234")
        get-settings-chan (async/chan)
        set-settings-chan (async/chan)
        site1-regulator (-> (default-regulator/->DefaultRegulator car (first solar-sites))
                            (regulator/with-regulation-creater (simple-regulation-creater/->SimpleRegulationCreater 1000 8 8)))
        site2-regulator (-> (default-regulator/->DefaultRegulator car (second solar-sites))
                            (regulator/with-regulation-creater (target-time-regulation-creater/->TargetTimeRegulationCreater get-settings-chan)))
        error-chan (async/chan (async/dropping-buffer 1))
        new-car-state-chan (async/chan (async/sliding-buffer 1))
        current-car-state-chan (async/chan)
        new-site1-data-chan (async/chan (async/sliding-buffer 1))
        current-site1-data-chan (async/chan)
        new-site2-data-chan (async/chan (async/sliding-buffer 1))
        current-site2-data-chan (async/chan)
        new-sms-chan (async/chan 5)
        log-chan (async/chan (async/sliding-buffer 10))]

    (process-sms-messages "" "" "" set-settings-chan error-chan log-chan)

    (provide-settings "" "" get-settings-chan set-settings-chan error-chan log-chan)

    (provide-new-car-state "" car new-car-state-chan error-chan log-chan)

    (provide-current-car-state "" current-car-state-chan new-car-state-chan error-chan log-chan)

    #_(provide-new-site-data "" (first solar-sites) new-site1-data-chan error-chan log-chan)

    #_(provide-current-site-data "" current-site1-data-chan new-site1-data-chan error-chan log-chan)

    (provide-new-site-data "" (second solar-sites) new-site2-data-chan error-chan log-chan)

    (provide-current-site-data "" current-site2-data-chan new-site2-data-chan error-chan log-chan)

    #_(regulate-car-charge-rate "" site1-regulator current-car-state-chan current-site1-data-chan error-chan log-chan)

    (regulate-car-charge-rate "" site2-regulator current-car-state-chan current-site2-data-chan error-chan log-chan)

    (log-loop :verbose log-chan error-chan)

    (.addShutdownHook
     (Runtime/getRuntime)
     (Thread.
      (fn []
        (log :info nil "Closing channels...")
        (async/close! set-settings-chan)
        (async/close! get-settings-chan)
        (async/close! new-sms-chan)
        (async/close! new-car-state-chan)
        (async/close! new-site1-data-chan)
        (async/close! current-car-state-chan)
        (async/close! current-site1-data-chan)
        (async/close! error-chan)
        (async/close! log-chan)
        (log :info nil "Channels closed."))))

    (let [e (async/<!! error-chan)]
      (when (not (nil? e))
        (let [stack-trace-string (with-out-str (clojure.stacktrace/print-stack-trace e))]
          (async/>!! log-chan {:level :error :message stack-trace-string})
          (time-utils/send-to-ntfy stack-trace-string))))))

