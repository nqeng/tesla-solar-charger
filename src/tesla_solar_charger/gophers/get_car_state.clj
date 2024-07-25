(ns tesla-solar-charger.gophers.get-car-state
  (:require
   [tesla-solar-charger.log :as log]
   [tesla-solar-charger.car.car :as car]
   [clojure.core.async :refer [>! close! sliding-buffer alts! timeout chan go]]
   [tesla-solar-charger.utils :as utils]))

(defn get-car-state
  [log-prefix car]
  (try
    (let [car-state (car/get-state car)]
      (log/info log-prefix (format "New car state:  %s" (into {} (take 3 car-state))))
      car-state)
    (catch clojure.lang.ExceptionInfo e
      (log/error log-prefix (format "Failed to get car state; %s" (ex-message e)))
      nil)
    (catch Exception e
      (log/error log-prefix (format "Failed to get car state; %s" (ex-message e)))
      nil)))

(defn get-new-car-state
  [car error-ch kill-ch]
  (let [log-prefix "get-new-car-state"
        output-ch (chan (sliding-buffer 1))]
    (go
      (log/info log-prefix "Process starting...")
      (loop [state {:sleep-for 0
                    :car car
                    :last-car-state nil}]
        (let [[_ ch] (alts! [kill-ch (timeout (:sleep-for state))])]
          (if (= ch kill-ch)
            (log/info log-prefix "Process dying...")
            (let [state (assoc state :sleep-for 0)
                  car (:car state)
                  [val ch] (alts! [kill-ch (go (get-car-state log-prefix car))])]
              (if (= ch kill-ch)
                (log/info log-prefix "Process dying...")
                (if (nil? val)
                  (recur state)
                  (let [car-state val
                        last-car-state (:last-car-state state)
                        state (assoc state :last-car-state car-state)]
                    (if (and (some? last-car-state)
                             (not (car/is-newer? car-state last-car-state)))
                      (do
                        (log/verbose log-prefix "No new car state")
                        (log/verbose log-prefix "Sleeping for 10s")
                        (let [state (assoc state :sleep-for 10000)]
                          (recur state)))
                      (if (not (>! output-ch car-state))
                        (do
                          (log/error log-prefix "Output channel was closed")
                          (>! error-ch (ex-info "Output channel was closed" {:type :channel-closed})))
                        (do
                          (log/verbose log-prefix "value -> channel")
                          (log/verbose log-prefix "Sleeping for 20s")
                          (let [state (assoc state :sleep-for 30000)]
                            (recur state))))))))))))
      (log/verbose log-prefix "Closing channel...")
      (close! output-ch)
      (log/verbose log-prefix "Process died"))
    output-ch))

