(ns tesla-solar-charger.gophers.get-site-data
  (:require
   [tesla-solar-charger.log :as log]
   [tesla-solar-charger.data-source.data-source :as data-source]
   [clojure.core.async :refer [>! alts! sliding-buffer chan go timeout close!]]
   [tesla-solar-charger.utils :as utils]))

(defn is-data-point-newer?
  [data-point1 data-point2]
  (.isAfter (:timestamp data-point1) (:timestamp data-point2)))

(defn get-data-point
  [data-source]
  (try
    (let [data-point (data-source/get-latest-data-point data-source)]
      {:val data-point :err nil})
    (catch clojure.lang.ExceptionInfo e
      #_(log/error log-prefix (format "Failed to get site data; %s" (ex-message e)))
      {:val nil :err e})
    (catch Exception e
      #_(log/error log-prefix (format "Failed to get site data; %s" (ex-message e)))
      {:val nil :err e})))

(defn get-new-site-data
  [data-source error-ch kill-ch]
  (let [log-prefix "get-new-site-data"
        output-ch (chan (sliding-buffer 1))]
    (go
      (log/info log-prefix "Process starting...")
      (loop [sleep-for 0
             last-data-point nil]
        (let [result-ch (go
                          (log/verbose log-prefix (format "Sleeping for %dms" sleep-for))
                          (Thread/sleep (* 1000 sleep-for))
                          (get-data-point data-source))
              [val ch] (alts! [kill-ch result-ch])]
          (if (= ch kill-ch)
            (log/info log-prefix "Process dying...")
            (let [{err :err data-point :data-point} val]
              (if (some? err)
                (do
                  (log/error log-prefix (format "Failed to get site data; %s" (ex-message err)))
                  (recur 10 last-data-point))
                (if (and (some? last-data-point)
                         (not (is-data-point-newer? data-point last-data-point)))
                  (do
                    (log/verbose log-prefix "No new site data")
                    (recur 30 last-data-point))
                  (do
                    (log/info log-prefix (format "New solar data: %s" data-point))
                    (let [success (>! output-ch data-point)]
                      (if (not success)
                        (do
                          (log/error log-prefix "Output channel was closed")
                          (>! error-ch (ex-info "Output channel was closed" {:type :channel-closed})))
                        (do
                          (log/verbose log-prefix "value -> channel")
                          (recur 30 data-point)))))))))))

      (log/verbose log-prefix "Closing channel...")
      (close! output-ch)
      (log/verbose log-prefix "Process died"))
    output-ch))

