(ns tesla-solar-charger.gophers.record-data
  (:require
    [taoensso.timbre :as timbre :refer [infof debugf errorf]]
    [tesla-solar-charger.recorder.recorder :as recorder]
    [better-cond.core :refer [cond] :rename {cond better-cond}]
    [tesla-solar-charger.utils :as utils]
    [clj-http.client :as client]
    [clojure.core.async :refer [go alts! close! chan >! go-loop timeout <!]]))

(defn timer
  [seconds kill-ch]
  (let [output-ch (chan)]
    (close!
      (go
        (loop []
          (let [timeout-ch (timeout (* 1000 seconds))
                [val ch] (alts! [kill-ch timeout-ch])]
            (if (= kill-ch ch)
              (close! timeout-ch)
              (let [[val ch] (alts! [[output-ch true] kill-ch])]
                (when (not= kill-ch ch)
                  (recur))))))
        (close! output-ch)))
    output-ch))

(defn record-data
  [recorder car-state-ch data-point-ch kill-ch prefix]
  (let [timer-ch (timer 60 kill-ch)]
    (close! 
      (go
        (infof "[%s] Process started" prefix)
        (loop [recorder recorder
               ?last-car-state nil
               ?last-data-point nil]
          (better-cond

            :do (debugf "[%s] Waiting for value..." prefix)

            :let [[val ch] (alts! [car-state-ch data-point-ch kill-ch timer-ch])]

            :do (debugf "[%s] Took value off channel" prefix)

            (= kill-ch ch) (infof "[%s] Received kill signal" prefix)

            (nil? val) (errorf "[%s] Input channel was closed" prefix)

            (= car-state-ch ch) (recur recorder val ?last-data-point)

            (= data-point-ch ch) (recur recorder ?last-car-state val)

            :do (infof "[%s] Recording data..." prefix)

            :let [result-ch (go (recorder/record-data recorder ?last-car-state ?last-data-point))]
            :let [[val ch] (alts! [kill-ch result-ch])]

            :do (close! result-ch)

            (= kill-ch ch) (infof "[%s] Received kill signal" prefix)

            :let [{recorder :obj err :err} val]

            (some? err) (errorf "[%s] Failed to record data; %s" prefix err)

            :do (infof "[%s] Successfully recorded data")

            (recur recorder ?last-car-state ?last-data-point)))

        (infof "[%s] Process ended" prefix)))))

