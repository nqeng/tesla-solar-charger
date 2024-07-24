(ns tesla-solar-charger.gophers.get-car-state
  (:require
   [tesla-solar-charger.log :as log]
   [tesla-solar-charger.interfaces.car :as Icar]
   [clojure.core.async :refer [>! close! sliding-buffer alts! timeout chan go]]
   [tesla-solar-charger.utils :as utils]))

(defn get-new-car-state
  [car error-ch kill-ch]
  (let [log-prefix "get-new-car-state"
        output-ch (chan (sliding-buffer 1))]
    (go
      (log/info log-prefix "Process starting...")
      (loop [state {:car car
                    :last-car-state nil}]
        (let [[_ ch] (alts! [kill-ch (timeout 0)])]
          (if (= ch kill-ch)
            (log/info log-prefix "Process dying...")
            (let [car (:car state)
                  last-car-state (:last-car-state state)
                  func (partial Icar/get-state car)
                  [error car-state] (utils/try-return-error func)]
              (if (some? error)
                (do
                  (log/error log-prefix (format "Failed to get car state; %s" (ex-message error)))
                  (recur state))
                (let [new-state (-> state
                                    (assoc :last-car-state car-state))]
                  (log/verbose log-prefix (format "Last car state: %s" (into {} (take 3 last-car-state))))
                  (log/info log-prefix (format "New car state:  %s" (into {} (take 3 car-state))))
                  (if (and (some? last-car-state)
                           (not (Icar/is-newer? car-state last-car-state)))
                    (do
                      (log/verbose log-prefix "No new car state")
                      (log/verbose log-prefix "Sleeping for 10s")
                      (Thread/sleep 10000)
                      (recur new-state))
                    (if (not (>! output-ch car-state))
                      (do
                        (log/error log-prefix "Output channel was closed")
                        (>! error-ch (ex-info "Output channel was closed" {:type :channel-closed})))
                      (do
                        (log/verbose log-prefix "value -> channel")
                        (log/verbose log-prefix "Sleeping for 20s")
                        (Thread/sleep 20000)
                        (recur new-state))))))))))
      (log/verbose log-prefix "Closing channel...")
      (close! output-ch)
      (log/verbose log-prefix "Process died"))
    output-ch))

