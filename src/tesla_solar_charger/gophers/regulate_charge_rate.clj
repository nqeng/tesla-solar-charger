(ns tesla-solar-charger.gophers.regulate-charge-rate
  (:require
   [tesla-solar-charger.log :as log]
   [tesla-solar-charger.regulator.regulator :refer [make-regulation-from-new-car-state make-regulation-from-new-data-point]]
   [clojure.core.async :as async :refer [close! chan alts! >! go]]))

(defn regulate-charge-rate
  [regulator car-state-ch data-point-ch charge-power-ch kill-ch log-prefix]
  (go
    (log/info log-prefix "Process starting...")
    (loop [regulator regulator]
      (let [[val ch] (alts! [kill-ch car-state-ch data-point-ch])]
        (if (= ch kill-ch)
          (log/info log-prefix "Process dying...")
          (if (nil? val)
            (log/error log-prefix "Input channel was closed")
            (if (= ch car-state-ch)
              (do
                (log/info log-prefix "Regulating new car state")
                (let [car-state val
                      [regulator regulation] (make-regulation-from-new-car-state regulator car-state)
                      message (:message regulation)
                      new-charge-power-watts (:new-charge-power-watts regulation)]
                  (when (some? new-charge-power-watts)
                    (>! charge-power-ch new-charge-power-watts))
                  (when (some? message)
                    (log/info log-prefix message))
                  (recur regulator)))
              (do
                (log/info log-prefix "Regulating new solar data")
                (let [data-point val
                      [regulator regulation] (make-regulation-from-new-data-point regulator data-point)
                      message (:message regulation)
                      new-charge-power-watts (:new-charge-power-watts regulation)]
                  (when (some? new-charge-power-watts)
                    (>! charge-power-ch new-charge-power-watts))
                  (when (some? message)
                    (log/info log-prefix message))
                  (recur regulator))))))))
    (log/info log-prefix "Process died")))
