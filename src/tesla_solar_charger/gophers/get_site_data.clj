(ns tesla-solar-charger.gophers.get-site-data
  (:require
   [tesla-solar-charger.log :as log]
   [tesla-solar-charger.data-source.data-source :as data-source]
   [clojure.core.async :refer [>! alts! sliding-buffer chan go close!]]))

(defn is-data-point-newer?
  [data-point1 data-point2]
  (.isAfter (:timestamp data-point1) (:timestamp data-point2)))

(defn get-data-point
  [data-source]
  (try
    (let [data-point (data-source/get-latest-data-point data-source)]
      {:val data-point :err nil})
    (catch clojure.lang.ExceptionInfo e
      {:val nil :err e})
    (catch Exception e
      {:val nil :err e})))

(defn make-data-point-message
  [data-point]
  (format
   "Excess power is %.2fW as of %s"
   (:excess-power-watts data-point) (:timestamp data-point)))

(defn fetch-latest-solar-data
  [data-source output-ch kill-ch]
  (let [log-prefix "fetch-solar-data"]
    (go
      (log/info log-prefix "Process starting...")
      (loop [sleep-for 0]
        (let [result-ch (go
                          (Thread/sleep sleep-for)
                          (get-data-point data-source))
              [val ch] (alts! [kill-ch result-ch])]
          (if (= ch kill-ch)
            (log/info log-prefix "Process dying...")
            (let [{err :err data-point :val} val]
              (if (some? err)
                (do
                  (log/error log-prefix (format "Failed to fetch solar data; %s" (ex-message err)))
                  (recur 10000))
                (do
                  (log/verbose log-prefix "Fetched solar data")
                  (>! output-ch data-point)
                  (recur 10000)))))))
      (log/info log-prefix "Process died"))))

(defn filter-new-solar-data
  [input-ch output-ch kill-ch]
  (let [log-prefix "filter-new-solar-data"]
    (go
      (log/info log-prefix "Process starting...")
      (loop [last-data-point nil]
        (let [[val ch] (alts! [kill-ch input-ch])]
          (if (= ch kill-ch)
            (log/info log-prefix "Process dying...")
            (let [data-point val]
              (if (and (some? last-data-point)
                       (not (is-data-point-newer? data-point last-data-point)))
                (do
                  (log/verbose log-prefix "No new solar data")
                  (recur last-data-point))
                (do
                  (log/info log-prefix (format "Received new solar data: %s" (make-data-point-message data-point)))
                  (>! output-ch data-point)
                  (recur data-point)))))))
      (log/info log-prefix "Process died"))))

(defn fetch-new-solar-data
  [data-source output-ch kill-ch]
  (let [latest-data-point-ch (chan)]
    (fetch-latest-solar-data data-source latest-data-point-ch kill-ch)
    (filter-new-solar-data latest-data-point-ch output-ch kill-ch)))

