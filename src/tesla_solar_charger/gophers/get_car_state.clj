(ns tesla-solar-charger.gophers.get-car-state
  (:require
   [tesla-solar-charger.log :as log]
   [tesla-solar-charger.car.car :as car]
   [clojure.core.async :refer [>! close! sliding-buffer alts! timeout chan go]]
   [tesla-solar-charger.utils :as utils]))

(defn get-car-state
  [car]
  (try
    (let [car-state (car/get-state car)]
      {:err nil :val car-state})
    (catch clojure.lang.ExceptionInfo err
      {:err err :val nil})
    (catch Exception err
      {:err err :val nil})))

(defn is-car-state-newer?
  [car-state last-car-state]
  (.isAfter (:timestamp car-state) (:timestamp last-car-state)))

(defn get-new-car-state
  [car err-ch kill-ch]
  (let [log-prefix "get-new-car-state"
        output-ch (chan (sliding-buffer 1))]
    (go
      (log/info log-prefix "Process starting...")
      (loop [sleep-for 0
             last-car-state nil]
        (let [result-ch (go
                          (log/info log-prefix (format "Sleeping for %dms" sleep-for))
                          (Thread/sleep (* 1000 sleep-for))
                          (get-car-state car))
              [val ch] (alts! [kill-ch result-ch])]
          (if (= ch kill-ch)
            (log/info log-prefix "Process dying...")
            (let [{err :err car-state :car-state} val]
              (if (some? err)
                (do
                  (log/error log-prefix (format "Failed to get car state; %s" (ex-message err)))
                  (recur 10 last-car-state))
                (if (and (some? last-car-state) 
                         (not (is-car-state-newer? car-state last-car-state)))
                  (do
                    (log/verbose log-prefix "No new car state")
                    (recur 30 last-car-state))
                  (do
                    (log/info log-prefix (format "New car state: %s" (into {} (take 3 car-state))))
                    (let [success (>! output-ch car-state)]
                      (if (not success)
                        (do
                          (log/error log-prefix "Output channel was closed")
                          (>! err-ch (ex-info "Output channel was closed" {:type :channel-closed})))
                        (do
                          (log/verbose log-prefix "value -> channel")
                          (recur car-state 30)))))))))))
      (log/verbose log-prefix "Closing channel...")
      (close! output-ch)
      (log/verbose log-prefix "Process died"))
    output-ch))

