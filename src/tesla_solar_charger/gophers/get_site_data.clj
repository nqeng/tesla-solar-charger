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
   "Excess power is %.2fW"
   (:excess-power-watts data-point)))

(defn fetch-solar-data
  [data-source kill-ch]
  (let [log-prefix "fetch-solar-data"
        output-ch (chan)]
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
                  (log/info log-prefix "Fetched solar data")
                  (>! output-ch data-point)
                  (recur 10000)))))))
      (log/info log-prefix "Closing output channel...")
      (close! output-ch)
      (log/info log-prefix "Process died"))
    output-ch))

(defn filter-new-solar-data
  [input-ch kill-ch]
  (let [log-prefix "filter-new-solar-data"
        output-ch (chan)]
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
                  (log/info log-prefix "No new solar data")
                  (recur last-data-point))
                (do
                  (log/info log-prefix "Received new solar data")
                  (log/info log-prefix (make-data-point-message data-point))
                  (>! output-ch data-point)
                  (recur data-point)))))))
      (log/info log-prefix "Closing output channel...")
      (close! output-ch)
      (log/info log-prefix "Process died"))
    output-ch))

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
                (cond
                  (nil? last-data-point)
                  (do
                    (log/info "Received fist solar data")
                    (>! output-ch data-point)
                    (recur 30 data-point))

                  (not (is-data-point-newer? data-point last-data-point))
                  (do
                    (log/info "No new solar data available")
                    (>! output-ch data-point)
                    (recur 30 last-data-point))

                  :else
                  (do
                    (log/verbose log-prefix "Received new solar data")
                    (recur 30 data-point))))))))
      (log/verbose log-prefix "Closing channel...")
      (close! output-ch)
      (log/verbose log-prefix "Process died"))
    output-ch))

