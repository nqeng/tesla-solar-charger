(ns tesla-solar-charger.gophers.get-car-state
  (:require
   [better-cond.core :refer [cond] :rename {cond better-cond}]
   [tesla-solar-charger.log :as log]
   [tesla-solar-charger.interfaces.car :as Icar]
   [clojure.core.async :as async]
   [tesla-solar-charger.utils :as utils]))

(defn whichever-car-state-is-newer
  [car-state1 car-state2]
  (if (Icar/is-newer? car-state1 car-state2)
    car-state1
    car-state2))

(defn run
  [state]
  (let [car (:car state)
        last-car-state (:last-car-state state)]
    (try
      (let [next-run-at (utils/time-after-seconds 10)
            [car car-state] (Icar/get-state car)
            state (assoc state :car car)
            next-car-state (whichever-car-state-is-newer car-state last-car-state)
            value-to-output (if (Icar/is-newer? car-state last-car-state)
                              car-state
                              nil)
            state (assoc state :last-car-state next-car-state)]
        (log/verbose (format "Last car state: %s"
                             (if (some? last-car-state)
                               (utils/string-this last-car-state)
                               "none")))
        (log/verbose (format "New car state:  %s"
                             (utils/string-this car-state)))
        [state value-to-output true next-run-at])
      (catch Exception e
        (log/error (ex-message e))
        [state nil true nil])
      (catch clojure.lang.ExceptionInfo e
        (log/error (ex-message e))
        [state nil true nil]))))

(def state {:car nil
            :last-car-state nil})

(defn get-new-car-state
  [log-prefix car error-ch kill-ch]
  (let [output-ch (async/chan)]
    (async/go
      (try
        (loop [state (-> state
                         (assoc :car car))]
          (let [[_ ch] (async/alts! [kill-ch (async/timeout 0)])]
            (when-not (= ch kill-ch)
              (let [[new-state value should-continue next-run-at] (run state)]
                (when (some? value)
                  (if-let [success (async/>! output-ch value)]
                    (log/verbose "value -> channel")
                    (throw utils/output-channel-closed)))
                (when should-continue
                  (when (some? next-run-at)
                    (log/verbose (format "Sleeping until %s" (utils/format-time next-run-at)))
                    (utils/sleep-until next-run-at))
                  (recur new-state))))))
        (catch clojure.lang.ExceptionInfo e
          (async/>! error-ch e))
        (catch Exception e
          (async/>! error-ch e)))
      (log/verbose "Closing channel...")
      (async/close! output-ch)
      (log/verbose "Done."))
    output-ch))

(comment
  (do
    (require '[tesla-solar-charger.implementations.car.tesla :refer [new-Tesla]])
    (require '[tesla-solar-charger.gophers.utils :refer [print-values]])
    (let [error-ch (async/chan (async/sliding-buffer 1))
          kill-ch (async/chan)
          car (new-Tesla "LRW3F7EKXPC780478" "P85JQZRL97qQ4KO6jVfODJnrIoSYUKtU")
          output-ch (get-new-car-state "" car error-ch kill-ch)]

      (print-values "Error: %s" error-ch)

      (print-values "Took %s" output-ch)

      (println "Fin.")

      (Thread/sleep 60000)

      (println "Killing program...")
      (async/>!! kill-ch :DIE))))

