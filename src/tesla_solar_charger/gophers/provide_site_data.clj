(ns tesla-solar-charger.gophers.provide-site-data
  (:require
   [tesla-solar-charger.interfaces.site :as site]
   [clojure.core.async :as async]
   [tesla-solar-charger.time-utils :as time-utils]))

(defn provide-new-site-data
  [log-prefix site output-chan error-chan log-chan]
  (async/go
    (try
      (loop [site site]
        (async/>! log-chan {:level :info :prefix log-prefix :message "Retrieving new data..."})
        (let [request {:start-time (java.time.LocalDateTime/now)
                       :end-time (java.time.LocalDateTime/now)}
              [site site-data] (site/get-data site request)]
          (async/>! log-chan {:level :info :prefix log-prefix :message "Got new data"})
          (async/>! log-chan {:level :verbose :prefix log-prefix :message site-data})
          (when (and (some? site-data)
                     (false? (async/>! output-chan site-data)))
            (throw (ex-info "Channel closed!" {})))
          (when-let [next-data-available-time (:next-data-available-time site)]
            (when (.isAfter next-data-available-time (java.time.LocalDateTime/now))
              (async/>! log-chan {:level :info :prefix log-prefix :message (format "Sleeping until %s"
                                                             (time-utils/format-time "yyyy-MM-dd HH:mm:ss" next-data-available-time))})
              (Thread/sleep (time-utils/millis-between-times (java.time.LocalDateTime/now) next-data-available-time))))
          (recur site)))
      (catch clojure.lang.ExceptionInfo e
        (async/>! error-chan e))
      (catch Exception e
        (async/>! error-chan e)))))

(defn provide-current-site-data
  [log-prefix output-chan new-data-chan error-chan log-chan]
  (async/go
    (try
      (loop [last-site-data nil]
        (if (nil? last-site-data)
          (do
            (async/>! log-chan {:level :info :prefix log-prefix :message "Waiting for initial data..."})
            (let [initial-data (async/<! new-data-chan)]
              (when (nil? initial-data)
                (throw (ex-info "Channel closed!" {})))
              (async/>! log-chan {:level :info :prefix log-prefix :message "Received initial data"})
              (async/>! log-chan {:level :verbose :prefix log-prefix :message initial-data})
              (recur initial-data)))
          (do
            (async/>! log-chan {:level :info :prefix log-prefix :message "Waiting..."})
            (let [[value chan] (async/alts! [[output-chan last-site-data] new-data-chan])]
              (if (= output-chan chan)
                (let [success value]
                  (when (false? success)
                    (throw (ex-info "Channel closed!" {})))
                  (async/>! log-chan {:level :info :prefix log-prefix :message "Provided current data"})
                  (recur last-site-data))
                (let [new-data value]
                  (when (nil? new-data)
                    (throw (ex-info "Channel closed!" {})))
                  (async/>! log-chan {:level :info :prefix log-prefix :message "Received new data"})
                  (async/>! log-chan {:level :verbose :prefix log-prefix :message new-data})
                  (recur new-data)))))))
      (catch clojure.lang.ExceptionInfo e
        (async/>! error-chan e))
      (catch Exception e
        (async/>! error-chan e)))))

