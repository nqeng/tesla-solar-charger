(ns tesla-solar-charger.gophers.get-car-state
  (:require
   [tesla-solar-charger.log :as log]
   [tesla-solar-charger.car-data-source.car-data-source :refer [get-latest-car-state]]
   [clojure.core.async :refer [>! close! timeout alts! chan go]]))

(defn perform-and-return-error
  [foo]
  (try
    (let [result (foo)]
      {:err nil :val result})
    (catch clojure.lang.ExceptionInfo err
      {:err err :val nil})
    (catch Exception err
      {:err err :val nil})))

(defn is-car-state-newer?
  [car-state last-car-state]
  (.isAfter (:timestamp car-state) (:timestamp last-car-state)))

(defn make-car-state-message
  [car-state]
  (format
   "Car is at %s (%s)"
   (:readable-location-name car-state)
   (if (:is-charging car-state) (format "charging at %.2fW" (float (:charge-power-watts car-state))) "not charging")))

(defn fetch-new-car-state
  [data-source output-ch kill-ch]
  (let [log-prefix "fetch-new-car-state"]
    (go
      (log/info log-prefix "Process starting...")
      (loop [sleep-for 0
             last-car-state nil]
        (let [timeout-ch (timeout (* 1000 sleep-for))
              [val ch] (alts! [kill-ch timeout-ch])]
          (if (= ch kill-ch)
            (log/info log-prefix "Process dying...")
            (let [result-ch (go (perform-and-return-error (partial get-latest-car-state data-source)))
                  [val ch] (alts! [kill-ch result-ch])]
              (if (= ch kill-ch)
                (log/info log-prefix "Process dying...")
                (let [{err :err car-state :val} val]
                  (if (some? err)
                    (do
                      (log/error log-prefix (format "Failed to fetch car state; %s" (ex-message err)))
                      (recur 60 last-car-state))
                    (if (and (some? last-car-state)
                             (not (is-car-state-newer? car-state last-car-state)))
                      (do
                        (log/info log-prefix "No new car state; sleeping for 60s")
                        (recur 60 last-car-state))
                      (do
                        (log/info log-prefix (format "Received new car state: %s" (make-car-state-message car-state)))
                        (>! output-ch car-state)
                        (recur 10 car-state))))))))))
      (log/info log-prefix "Process died"))))

(defn poll-latest-car-state
  [car output-ch kill-ch]
  (let [log-prefix "poll-latest-car-state"]
    (go
      (log/info log-prefix "Process starting...")
      (loop [sleep-for 0]
        (let [result-ch (go
                          (Thread/sleep sleep-for)
                          (perform-and-return-error (partial get-latest-car-state car)))
              [val ch] (alts! [kill-ch result-ch])]
          (if (= ch kill-ch)
            (log/info log-prefix "Process dying...")
            (let [{err :err car-state :val} val]
              (if (some? err)
                (do
                  (log/error log-prefix (format "Failed to fetch car state; %s" (ex-message err)))
                  (recur 10000))
                (do
                  (log/verbose log-prefix "Fetched car state")
                  (>! output-ch car-state)
                  (recur 10000)))))))
      (log/info log-prefix "Process died"))))

(defn filter-new-car-state
  [input-ch output-ch kill-ch]
  (let [log-prefix "filter-new-car-state"]
    (go
      (log/info log-prefix "Process starting...")
      (loop [last-car-state nil]
        (let [[val ch] (alts! [kill-ch input-ch])]
          (if (= ch kill-ch)
            (log/info log-prefix "Process dying...")
            (let [car-state val]
              (if (and (some? last-car-state)
                       (not (is-car-state-newer? car-state last-car-state)))
                (do
                  (log/verbose log-prefix "No new car state")
                  (recur last-car-state))
                (do
                  (log/info log-prefix (format "Received new car state: %s" (make-car-state-message car-state)))
                  (>! output-ch car-state)
                  (recur car-state)))))))
      (log/info log-prefix "Process died"))))

#_(defn fetch-new-car-state
  [data-source output-ch kill-ch]
  (let [latest-car-state-ch (chan)]
    (poll-latest-car-state data-source latest-car-state-ch kill-ch)
    (filter-new-car-state latest-car-state-ch output-ch kill-ch)))

