(ns tesla-solar-charger.gophers.set-charge-rate
  (:require
   [tesla-solar-charger.log :as log]
   [clojure.core.async :refer [>! go alts!]]
   [tesla-solar-charger.charger.charger :as charger]))

(defn set-charge-rate
  [car charger input-ch err-ch kill-ch]
  (let [log-prefix "set-charge-rate"]
    (go
      (log/info log-prefix "Process starting...")
      (loop [state {}]
        (let [[val ch] (alts! [kill-ch input-ch])]
          (if (= ch kill-ch)
            (log/info log-prefix "Process dying...")
            (let [power-watts val]
              (if (nil? power-watts)
                (do (log/error log-prefix "Input channel was closed")
                    (>! err-ch (ex-info "Input channel was closed" {:type :channel-closed})))
                (do
                  (log/info log-prefix (format "Setting charge rate to %.2fW..." power-watts))
                  (try
                    (charger/set-car-charge-power-watts charger car power-watts)
                    (log/info log-prefix "Successfully set charge rate")
                    (catch clojure.lang.ExceptionInfo e
                      (log/error log-prefix (format "Failed to set charge rate; %s" (ex-message e))))
                    (catch Exception e
                      (log/error log-prefix (format "Failed to set charge rate; %s" (ex-message e)))))
                  (recur state)))))))
      (log/verbose log-prefix "Process died"))))

