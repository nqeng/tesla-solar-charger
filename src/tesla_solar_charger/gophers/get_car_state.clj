(ns tesla-solar-charger.gophers.get-car-state
  (:require
   [tesla-solar-charger.interfaces.car :as car]
   [clojure.core.async :as async]
   [tesla-solar-charger.utils :as utils]))

(defn get-car-state
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
              (throw (ex-info "Channel closed!" {})))
            (when-let [next-state-available-time (utils/time-after-seconds 10)]
              (when (.isAfter next-state-available-time (java.time.LocalDateTime/now))
                (async/>! log-chan {:level :info
                                    :prefix log-prefix
                                    :message (format "Sleeping until %s"
                                                     (utils/format-time "yyyy-MM-dd HH:mm:ss" next-state-available-time))})
                (async/>! log-chan {:level :info
                                    :prefix log-prefix
                                    :message "..."})
                (Thread/sleep (utils/millis-between-times (java.time.LocalDateTime/now) next-state-available-time)))))
          (catch com.fasterxml.jackson.core.JsonParseException e
            (do
              (utils/send-to-ntfy "com.fasterxml.jackson.core.JsonParseException; failed to parse car state")
              (Thread/sleep 10000)))
          (catch clojure.lang.ExceptionInfo e
            (case (:type (ex-data e))
              :network-error

              (do
                (async/>! log-chan {:level :error
                                    :prefix log-prefix
                                    :message (ex-message e)})
                (Thread/sleep 10000))

              (throw e))))

        (recur car))
      (catch clojure.lang.ExceptionInfo e
        (async/>! error-chan e))
      (catch Exception e
        (async/>! error-chan e)))))

