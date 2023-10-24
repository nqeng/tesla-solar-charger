(ns tesla-solar-charger.gophers.get-site-data
  (:require
   [tesla-solar-charger.interfaces.site :as site]
   [clojure.core.async :as async]
   [tesla-solar-charger.utils :as utils]))

(defn get-site-data
  [log-prefix site output-chan error-chan log-chan]
  (async/go
    (try
      (loop [site site]
        (async/>! log-chan {:level :info :prefix log-prefix :message "Getting new data..."})
        (try
          (let [request {:start-time (java.time.LocalDateTime/now)
                         :end-time (java.time.LocalDateTime/now)}
                [site site-data] (site/get-data site request)]
            (async/>! log-chan {:level :info :prefix log-prefix :message "Got data"})
            (async/>! log-chan {:level :verbose :prefix log-prefix :message (format "{ excess=%s }"
                                                                                    (site/get-excess-power-watts (last (site/get-points site-data))))})
            (when (and (some? site-data)
                       (false? (async/>! output-chan site-data)))
              (throw (ex-info "Channel closed!" {})))
            (when-let [next-data-available-time (:next-data-available-time site)]
              (when (.isAfter next-data-available-time (java.time.LocalDateTime/now))
                (async/>! log-chan {:level :info
                                    :prefix log-prefix
                                    :message (format "Sleeping until %s"
                                                     (utils/format-time "yyyy-MM-dd HH:mm:ss" next-data-available-time))})
                (async/>! log-chan {:level :info
                                    :prefix log-prefix
                                    :message "..."})
                (Thread/sleep (utils/millis-between-times (java.time.LocalDateTime/now) next-data-available-time)))))
          (catch clojure.lang.ExceptionInfo e
            (case (:type (ex-data e))
              :network-error

              (do
                (Thread/sleep 10000)
                (async/>! log-chan {:level :error
                                    :prefix log-prefix
                                    :message (ex-message e)}))
              (throw e))))

        (recur site))
      (catch clojure.lang.ExceptionInfo e
        (async/>! error-chan e))
      (catch Exception e
        (async/>! error-chan e)))))

