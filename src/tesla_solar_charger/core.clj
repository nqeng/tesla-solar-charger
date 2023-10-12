(ns tesla-solar-charger.core
  (:gen-class)
  (:require
   [clj-http.client :as client]
   [tesla-solar-charger.gophers.provide-car-state :refer [provide-new-car-state provide-current-car-state]]
   [tesla-solar-charger.gophers.provide-site-data :refer [provide-new-site-data provide-current-site-data]]
   [tesla-solar-charger.gophers.regulate-charge-rate :refer [regulate-car-charge-rate]]
   [tesla-solar-charger.gophers.logger :refer [log-loop log]]
   [tesla-solar-charger.implementations.dummy-tesla :as dummy-tesla]
   [tesla-solar-charger.implementations.tesla :as tesla]
   [tesla-solar-charger.implementations.sungrow-site :as sungrow-site]
   [tesla-solar-charger.interfaces.regulator :as regulator]
   [tesla-solar-charger.implementations.simple-regulation-creater :as simple-regulation-creater]
   [tesla-solar-charger.implementations.target-time-regulation-creater :as target-time-regulation-creater]
   [tesla-solar-charger.implementations.default-regulator :as default-regulator]
   [tesla-solar-charger.time-utils :refer :all]
   [clojure.core.async :as async]))



(defn -main
  [& args]
  (println "Starting...")
  (let [car (dummy-tesla/->DummyTesla "1234")
        site1-regulator (-> (default-regulator/->DefaultRegulator car (first solar-sites))
                            (regulator/with-regulation-creater (simple-regulation-creater/->SimpleRegulationCreater 1000 8 8)))
        site2-regulator (-> (default-regulator/->DefaultRegulator car (second solar-sites))
                            (regulator/with-regulation-creater (simple-regulation-creater/->SimpleRegulationCreater 1000 8 8)))
        error-chan (async/chan (async/dropping-buffer 1))
        new-car-state-chan (async/chan (async/sliding-buffer 1))
        current-car-state-chan (async/chan)
        new-site1-data-chan (async/chan (async/sliding-buffer 1))
        current-site1-data-chan (async/chan)
        new-site2-data-chan (async/chan (async/sliding-buffer 1))
        current-site2-data-chan (async/chan)
        log-chan (async/chan 10)]

    (provide-new-car-state "New state (Tesla)" car new-car-state-chan error-chan log-chan)

    (provide-current-car-state "Current state (Tesla)" current-car-state-chan new-car-state-chan error-chan log-chan)

    (provide-new-site-data "New data (NQE)" (first solar-sites) new-site1-data-chan error-chan log-chan)

    (provide-current-site-data "Current data (NQE)" current-site1-data-chan new-site1-data-chan error-chan log-chan)

    (provide-new-site-data "New data (Summerfield)" (second solar-sites) new-site2-data-chan error-chan log-chan)

    (provide-current-site-data "Current data (Summerfield)" current-site2-data-chan new-site2-data-chan error-chan log-chan)

    (regulate-car-charge-rate "Regulator (NQE)" site1-regulator current-car-state-chan current-site1-data-chan error-chan log-chan)

    (regulate-car-charge-rate "Regulator (Summerfield)" site2-regulator current-car-state-chan current-site2-data-chan error-chan log-chan)

    (async/go
      (log-loop
       log-chan
       error-chan))

    (.addShutdownHook
     (Runtime/getRuntime)
     (Thread.
      (fn []
        (log :info nil "Closing channels...")
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
          (try (client/post "https://ntfy.sh/github-nqeng-tesla-solar-charger" {:body stack-trace-string})))))))

