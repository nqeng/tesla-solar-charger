(ns tesla-solar-charger.gophers.regulate-charge-rate
  (:require
   [tesla-solar-charger.interfaces.regulator :as regulator]
   [clojure.core.async :as async]))

(defn regulate-car-charge-rate
  [log-prefix regulator current-car-state-chan current-site-data-chan error-chan log-chan]
  (async/go
    (try
      (loop [regulator regulator]
        (let [car-state (async/<! current-car-state-chan)
              site-data (async/<! current-site-data-chan)]
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


