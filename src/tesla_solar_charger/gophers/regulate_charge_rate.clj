(ns tesla-solar-charger.gophers.regulate-charge-rate
  (:require
   [tesla-solar-charger.interfaces.regulator :as regulator]
   [clojure.core.async :as async]))

(defn regulate-car-charge-rate
  [log-prefix regulator car-state-chan site-data-chan error-chan log-chan]
  (async/go
    (try
      (loop [regulator regulator]
        (async/>! log-chan {:level :info :prefix log-prefix :message "Waiting..."})
        (let [car-state (async/<! car-state-chan)
              site-data (async/<! site-data-chan)]
          (when (nil? car-state)
            (throw (ex-info "Channel closed" {})))
          (when (nil? site-data)
            (throw (ex-info "Channel closed" {})))

          (let [regulator (regulator/regulate regulator car-state site-data log-chan log-prefix)]
            (Thread/sleep 2000)
            (recur regulator))))

      (catch clojure.lang.ExceptionInfo e
        (async/>! error-chan e))
      (catch Exception e
        (async/>! error-chan e)))))


