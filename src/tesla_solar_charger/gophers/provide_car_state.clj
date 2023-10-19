(ns tesla-solar-charger.gophers.provide-car-state
  (:require
   [tesla-solar-charger.interfaces.car :as car]
   [clojure.core.async :as async]
   [tesla-solar-charger.time-utils :as time-utils]))

(defn provide-new-car-state
  [log-prefix car output-chan error-chan log-chan]
  (async/go
    (try
      (loop [car car]
        (async/>! log-chan {:level :info :prefix log-prefix :message "Retrieving new state..."})
        (try
          (let [car-state (car/get-state car)]
            (async/>! log-chan {:level :info :prefix log-prefix :message "Got new state"})
            (async/>! log-chan {:level :verbose :prefix log-prefix :message car-state})
            (when (and (some? car-state)
                       (false? (async/>! output-chan car-state)))
              (throw (ex-info "Channel closed!" {}))))
          (catch com.fasterxml.jackson.core.JsonParseException e
            (time-utils/send-to-ntfy "com.fasterxml.jackson.core.JsonParseException; failed to parse car state")))

        (when-let [next-state-available-time (time-utils/time-after-seconds 10)]
          (when (.isAfter next-state-available-time (java.time.LocalDateTime/now))
            (async/>! log-chan {:level :info
                                :prefix log-prefix
                                :message (format "Sleeping until %s"
                                                 (time-utils/format-time "yyyy-MM-dd HH:mm:ss" next-state-available-time))})
            (Thread/sleep (time-utils/millis-between-times (java.time.LocalDateTime/now) next-state-available-time))))
        (recur car))
      (catch clojure.lang.ExceptionInfo e
        (async/>! error-chan e))
      (catch Exception e
        (async/>! error-chan e)))))

(defn provide-current-car-state
  [log-prefix output-chan new-state-chan error-chan log-chan]
  (async/go
    (try
      (loop [last-car-state nil]
        (if (nil? last-car-state)
          (do
            (async/>! log-chan {:level :info :prefix log-prefix :message "Waiting for initial state..."})
            (let [initial-state (async/<! new-state-chan)]
              (when (nil? initial-state)
                (throw (ex-info "Channel closed!" {})))
              (async/>! log-chan {:level :info :message :prefix log-prefix "Received initial state"})
              (async/>! log-chan {:level :verbose :prefix log-prefix :message initial-state})
              (recur initial-state)))
          (do
            (async/>! log-chan {:level :info :prefix log-prefix :message "Waiting..."})
            (let [[value chan] (async/alts! [[output-chan last-car-state] new-state-chan])]
              (if (= output-chan chan)
                (let [success value]
                  (when (false? success)
                    (throw (ex-info "Channel closed!" {})))
                  (async/>! log-chan {:level :info :prefix log-prefix :message "Provided current state"})
                  (recur last-car-state))
                (let [new-state value]
                  (when (nil? new-state)
                    (throw (ex-info "Channel closed!" {})))
                  (async/>! log-chan {:level :info :prefix log-prefix :message "Received new state"})
                  (async/>! log-chan {:level :verbose :prefix log-prefix :message new-state})
                  (recur new-state)))))))
      (catch clojure.lang.ExceptionInfo e
        (async/>! error-chan e))
      (catch Exception e
        (async/>! error-chan e)))))

