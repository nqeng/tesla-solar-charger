(ns tesla-solar-charger.gophers.set-charge-rate
  (:require
   [clj-http.client :as client]
   [tesla-solar-charger.log :as log]
   [tesla-solar-charger.utils :as utils]
   [clojure.core.async :refer [sliding-buffer chan close! <! >! go alts!]]))

(defn set-charge-current
  [new-current-amps tesla-vin auth-token]
  (let [url (str "https://api.tessie.com/" tesla-vin "/command/set_charging_amps")
        query-params {:retry-duration "40"
                      :wait-for-completion "true"
                      :amps new-current-amps}
        headers {:oauth-token auth-token
                 :accept :json
                 :query-params query-params}]
    (client/post url headers)))

(defn set-charge-rate
  [tesla-vin auth-token err-ch kill-ch]
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
                    (set-charge-current charge-current-amps tesla-vin auth-token)
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

