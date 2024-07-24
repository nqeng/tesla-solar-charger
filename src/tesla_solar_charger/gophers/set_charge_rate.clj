(ns tesla-solar-charger.gophers.set-charge-rate
  (:require
   [tesla-solar-charger.log :as log]
   [tesla-solar-charger.utils :as utils]
   [tesla-solar-charger.interfaces.car :as ICar]
   [clojure.core.async :refer [sliding-buffer chan close! <! >! go alts!]]))

(defn set-charge-rate
  [car err-ch kill-ch]
  (let [log-prefix "set-charge-rate"
        input-ch (chan (sliding-buffer 1))]
    (go
      (log/info log-prefix "Process starting...")
      (loop [state {}]
        (let [[_ ch] (alts! [kill-ch] :default nil)]
          (if (= ch kill-ch)
            (log/info log-prefix "Process dying...")
            (let [power-watts (<! input-ch)]
              (if (nil? power-watts)
                (do (log/error log-prefix "Input channel was closed")
                    (>! err-ch (ex-info "Input channel was closed" {:type :channel-closed})))
                (let [charge-current-amps (utils/watts-to-amps-three-phase-australia power-watts)]
                  (log/info log-prefix (format "Setting charge rate to %dA..." charge-current-amps))
                  (try
                    (ICar/set-charge-current car charge-current-amps)
                    (log/info log-prefix "Successfully set charge rate")
                    (catch clojure.lang.ExceptionInfo e
                      (log/error log-prefix (format "Failed to set charge rate; %s" (ex-message e))))
                    (catch Exception e
                      (log/error log-prefix (format "Failed to set charge rate; %s" (ex-message e)))))
                  (recur state)))))))
      (log/verbose log-prefix "Closing channel...")
      (close! input-ch)
      (log/verbose log-prefix "Process died"))
    input-ch))
