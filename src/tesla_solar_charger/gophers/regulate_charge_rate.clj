(ns tesla-solar-charger.gophers.regulate-charge-rate
  (:require
    [taoensso.timbre :as timbre :refer [infof errorf debugf]]
    [better-cond.core :refer [cond] :rename {cond better-cond}]
    [tesla-solar-charger.regulator.regulator :refer [make-regulation-from-new-car-state make-regulation-from-new-data-point]]
    [clojure.core.async :as async :refer [close! chan alts! >! go]]))

(defn regulate-charge-rate
  [regulator car-state-ch data-point-ch charge-power-ch kill-ch prefix]
  (close!
    (go
      (infof "[%s] Process started" prefix)
      (loop [regulator regulator]

        (better-cond
          :do (debugf "[%s] Waiting for value..." prefix)

          :let [[val ch] (alts! [kill-ch car-state-ch data-point-ch])]

          :do (debugf "[%s] Took value off channel" prefix)

          (= kill-ch ch) (infof "[%s] Received kill signal" prefix)

          (nil? val) (errorf "[%s] Input channel was closed" prefix)

          (= ch data-point-ch)
          (better-cond
            :let [data-point val]
            :let [[regulator regulation] (make-regulation-from-new-data-point regulator data-point)]
            :let [message (:message regulation)]
            :let [?new-charge-power-watts (:?new-charge-power-watts regulation)]

            :do (infof "[%s] Regulated new solar data; %s" prefix message)

            (nil? ?new-charge-power-watts) (recur regulator)

            :do (debugf "[%s] Putting value on channel..." prefix)

            :let [[val ch] (alts! [[charge-power-ch ?new-charge-power-watts] kill-ch])]

            (= kill-ch ch) (infof "[%s] Received kill signal" prefix)

            (false? val) (errorf "[%s] Output channel was closed" prefix)

            :do (debugf "[%s] Put value on channel" prefix)

            (recur regulator))

          :else
          (better-cond
            :let [car-state val]
            :let [[regulator regulation] (make-regulation-from-new-car-state regulator car-state)]
            :let [message (:message regulation)]
            :let [?new-charge-power-watts (:?new-charge-power-watts regulation)]

            :do (infof "[%s] Regulated new car state; %s" prefix message)

            (nil? ?new-charge-power-watts) (recur regulator)

            :do (debugf "[%s] Putting value on channel..." prefix)

            :let [[val ch] (alts! [[charge-power-ch ?new-charge-power-watts] kill-ch])]

            (= kill-ch ch) (infof "[%s] Received kill signal" prefix)

            (false? val) (errorf "[%s] Output channel was closed" prefix)

            :do (debugf "[%s] Put value on channel" prefix)

            (recur regulator))))

      (infof "[%s] Process ended" prefix))))

