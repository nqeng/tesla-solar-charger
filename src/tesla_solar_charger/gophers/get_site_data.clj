(ns tesla-solar-charger.gophers.get-site-data
  (:require
   [tesla-solar-charger.log :as log]
   [tesla-solar-charger.interfaces.site-data :as Isite-data]
   [clojure.core.async :refer [>! alts! sliding-buffer chan go close!]]
   [tesla-solar-charger.utils :as utils]))

(defn get-new-site-data
  [data-source error-ch kill-ch]
  (let [log-prefix "get-new-site-data"
        output-ch (chan (sliding-buffer 1))]
    (go
      (log/info log-prefix "Process starting...")
      (loop [state {:last-data-point nil}]
        (let [[_ ch] (alts! [kill-ch] :default nil)]
          (if (= ch kill-ch)
            (log/info log-prefix "Process dying...")
            (let [last-data-point (:last-data-point state)
                  func (partial Isite-data/get-latest-data-point data-source)
                  [error data-point] (utils/try-return-error func)]
              (if (some? error)
                (do
                  (log/error log-prefix (format "Failed to get site data; %s" (ex-message error)))
                  (log/info log-prefix "Sleeping for 10s")
                  (Thread/sleep 10000)
                  (recur state))
                (let [state (assoc state :last-data-point data-point)]
                  (log/verbose log-prefix (format "Last solar data: %s" last-data-point))
                  (log/info log-prefix (format "New solar data:  %s" data-point))
                  (if (and (some? last-data-point)
                           (not (Isite-data/is-newer? data-point last-data-point)))
                    (do
                      (log/verbose log-prefix "No new site data")
                      (log/verbose log-prefix "Sleeping for 30s")
                      (Thread/sleep 30000)
                      (recur state))
                    (if (false? (>! output-ch data-point))
                      (do
                        (log/error log-prefix "Output channel was closed")
                        (>! error-ch (ex-info "Output channel was closed" {:type :channel-closed})))
                      (do
                        (log/verbose log-prefix "value -> channel")
                        (log/verbose log-prefix "Sleeping for 30s")
                        (Thread/sleep 30000)
                        (recur state))))))))))
      (log/verbose log-prefix "Closing channel...")
      (close! output-ch)
      (log/verbose log-prefix "Process died"))
    output-ch))

