(ns tesla-solar-charger.gophers.regulate-charge-rate
  (:require
   [tesla-solar-charger.log :as log]
   [tesla-solar-charger.interfaces.regulator :as regulator]
   [clojure.core.async :as async]))

(defn regulate-charge-rate
  [log-prefix regulator car-state-chan site-data-chan error-chan]
  (async/go
    (try
      (loop [regulator regulator]
        (log/verbose "...")
        (let [car-state (async/<! car-state-chan)
              site-data (async/<! site-data-chan)]
          (when (nil? car-state)
            (throw (ex-info "Channel closed" {})))
          (when (nil? site-data)
            (throw (ex-info "Channel closed" {})))

          (let [regulator (try
                            (regulator/regulate regulator car-state site-data)
                            (catch clojure.lang.ExceptionInfo e
                              (case (:type (ex-data e))
                                :network-error
                                (do
                                  (log/error (ex-message e))
                                  regulator)

                                (throw e))))]

            (recur regulator))))

      (catch clojure.lang.ExceptionInfo e
        (async/>! error-chan e))
      (catch Exception e
        (async/>! error-chan e)))))


