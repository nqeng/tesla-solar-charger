(ns tesla-solar-charger.gophers.regulate-charge-rate
  (:require
   [taoensso.timbre :as timbre :refer [infof debugf errorf]]
   [tesla-solar-charger.regulator.regulator :refer [make-regulation-from-new-car-state make-regulation-from-new-data-point]]
   [clojure.core.async :as async :refer [close! chan alts! >! go]]))

(defn regulate-charge-rate
  [regulator car-state-ch data-point-ch charge-power-ch kill-ch prefix]
  (letfn [(info [msg] (timbre/info (format "[%s]" prefix) msg))
          (error [msg] (timbre/error (format "[%s]" prefix) msg))
          (debug [msg] (timbre/debug (format "[%s]" prefix) msg))]
    (go
      (infof "[%s] Process starting..." prefix)
      (loop [regulator regulator]
        (let [[val ch] (alts! [kill-ch car-state-ch data-point-ch])]
          (if (= ch kill-ch)
            (info "Process dying...")
            (if (nil? val)
              (error "Input channel was closed")
              (if (= ch car-state-ch)
                (let [car-state val
                      [regulator regulation] (make-regulation-from-new-car-state regulator car-state)
                      message (:message regulation)
                      new-charge-power-watts (:new-charge-power-watts regulation)]
                  (when (some? new-charge-power-watts)
                    (debug "Putting val on channel...")
                    (>! charge-power-ch new-charge-power-watts)
                    (debug "Put val on channel"))
                  (when (some? message)
                    (info (format "Regulated new car state; %s" message)))
                  (recur regulator))
                (let [data-point val
                      [regulator regulation] (make-regulation-from-new-data-point regulator data-point)
                      message (:message regulation)
                      new-charge-power-watts (:new-charge-power-watts regulation)]
                  (when (some? new-charge-power-watts)
                    (debug "Putting value on channel...")
                    (>! charge-power-ch new-charge-power-watts)
                    (debug "Put value on channel"))
                  (when (some? message)
                    (info (format "Regulated new solar data; %s" message)))
                  (recur regulator)))))))
      (info "Process died"))))
