(ns tesla-solar-charger.gophers.set-charge-rate
  (:require
    [taoensso.timbre :as timbre :refer [infof errorf debugf]]
    [better-cond.core :refer [cond] :rename {cond better-cond}]
    [tesla-solar-charger.car-charge-setter.car-charge-setter :refer [set-charge-power]]
    [clojure.core.async :refer [>! go alts!]]))

(defn perform-and-return-error
  [foo]
  (try
    (let [result (foo)]
      {:err nil :val result})
    (catch clojure.lang.ExceptionInfo err
      {:err err :val nil})
    (catch Exception err
      {:err err :val nil})))

(defn set-charge-rate
  [charge-setter input-ch kill-ch prefix]
  (go
    (infof "[%s] Process started" prefix )
    (loop []

      (better-cond
        :do (debugf "[%s] Taking value off channel..." prefix)

        :let [[val ch] (alts! [kill-ch input-ch])]
        (= ch kill-ch) (infof "[%s] Received kill signal" prefix)

        (nil? val) (errorf "[%s] Input channel was closed" prefix)

        :let [power-watts val]

        :do (infof "[%s] Setting charge rate to %.2fW..." prefix (float power-watts))

        :let [work #(set-charge-power charge-setter power-watts)]
        :let [result-ch (go (perform-return-error work))]
        :let [[val ch] (alts! [kill-ch result-ch])]

        (= ch kill-ch) (infof "[%s] Received kill signal" prefix)

        :let [{err :err} val]

        (some? err)
        (errorf "[%s] Failed to set charge rate; %s" prefix err)

        :do (infof "[%s] Successfully set charge rate" prefix)

        (recur)))

    (infof "Process ended" prefix)))
