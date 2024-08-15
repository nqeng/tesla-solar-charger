(ns tesla-solar-charger.gophers.set-charge-rate
  (:require
    [taoensso.timbre :as timbre :refer [infof errorf debugf]]
    [better-cond.core :refer [cond] :rename {cond better-cond}]
    [tesla-solar-charger.car-charge-setter.car-charge-setter :refer [set-charge-power]]
    [clojure.core.async :refer [>! go alts! close!]]))

(defn set-charge-rate
  [charge-setter input-ch kill-ch prefix]
  (close!
    (go
      (infof "[%s] Process started" prefix )
      (loop [charge-setter charge-setter]

        (better-cond
          :do (debugf "[%s] Waiting for value..." prefix)

          :let [[val ch] (alts! [kill-ch input-ch])]

          :do (debugf "[%s] Took value off channel" prefix)

          (= kill-ch ch) (infof "[%s] Received kill signal" prefix)

          (nil? val) (errorf "[%s] Input channel was closed" prefix)

          :let [power-watts val]

          :do (debugf "[%s] Setting charge rate to %.2fW..." prefix (float power-watts))

          :let [result-ch (go (set-charge-power charge-setter power-watts))]
          :let [[val ch] (alts! [kill-ch result-ch])]

          (= ch kill-ch) (do (infof "[%s] Received kill signal" prefix) (close! result-ch))

          :let [{charge-setter :obj err :err} val]

          (some? err) (errorf "[%s] Failed to set charge rate; %s" prefix err)

          :do (infof "[%s] Successfully set charge rate to %.2f" prefix (float power-watts))

          (recur charge-setter)))

      (infof "[%s] Process ended" prefix))))
