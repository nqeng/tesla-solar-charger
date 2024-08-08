(ns tesla-solar-charger.gophers.regulate-charge-rate
  (:require
    [taoensso.timbre :as timbre :refer [infof errorf debugf]]
    [better-cond.core :refer [cond] :rename {cond better-cond}]
    [tesla-solar-charger.regulator.regulator :refer [regulate-new-car-state regulate-new-data-point]]
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
            :let [regulator (regulate-new-data-point regulator data-point)]
            (recur regulator))

          :else
          (better-cond
            :let [car-state val]
            :let [regulator (regulate-new-car-state regulator car-state)]
            (recur regulator))))

      (infof "[%s] Process ended" prefix))))

