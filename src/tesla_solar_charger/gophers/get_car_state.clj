(ns tesla-solar-charger.gophers.get-car-state
  (:require
   [tesla-solar-charger.log :as log]
   [tesla-solar-charger.car.car :as car]
   [clojure.core.async :refer [>! close! sliding-buffer alts! timeout chan go]]
   [tesla-solar-charger.utils :as utils]))

(defn get-car-state
  [car]
  (try
    (let [car-state (car/get-state car)]
      {:err nil :val car-state})
    (catch clojure.lang.ExceptionInfo err
      {:err err :val nil})
    (catch Exception err
      {:err err :val nil})))

(defn is-car-state-newer?
  [car-state last-car-state]
  (.isAfter (:timestamp car-state) (:timestamp last-car-state)))

(defn make-car-state-message
  [car car-state]
  (format
   "%s is at %s (%s)"
   (car/get-name car)
   (:readable-location-name car-state)
   (if (:is-charging car-state) (format "charging at %dA" (:charge-current-amps car-state)) "not charging")))

(defn fetch-car-state
  [car kill-ch]
  (let [log-prefix "fetch-car-state"
        output-ch (chan)]
    (go
      (log/info log-prefix "Process starting...")
      (loop [sleep-for 0]
        (let [result-ch (go
                          (Thread/sleep sleep-for)
                          (get-car-state car))
              [val ch] (alts! [kill-ch result-ch])]
          (if (= ch kill-ch)
            (log/info log-prefix "Process dying...")
            (let [{err :err car-state :val} val]
              (if (some? err)
                (do
                  (log/error log-prefix (format "Failed to fetch car state; %s" (ex-message err)))
                  (recur 10000))
                (do
                  (log/info log-prefix "Fetched car state")
                  (>! output-ch car-state)
                  (recur 10000)))))))
      (log/info log-prefix "Closing output channel...")
      (close! output-ch)
      (log/info log-prefix "Process died"))
    output-ch))

(defn filter-new-car-state
  [car input-ch kill-ch]
  (let [log-prefix "filter-new-car-state"
        output-ch (chan)]
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
                  (log/info log-prefix "No new car state")
                  (recur last-car-state))
                (do
                  (log/info log-prefix "Received new car state")
                  (log/info log-prefix (make-car-state-message car car-state))
                  (>! output-ch car-state)
                  (recur car-state)))))))
      (log/info log-prefix "Closing output channel...")
      (close! output-ch)
      (log/info log-prefix "Process died"))
    output-ch))

(defn get-new-car-state
  [car err-ch kill-ch]
  (let [log-prefix "get-new-car-state"
        output-ch (chan (sliding-buffer 1))]
    (go
      (log/info log-prefix "Process starting...")
      (loop [sleep-for 0
             last-car-state nil]
        (let [#_result-ch #_(go
                              (log/info log-prefix (format "Sleeping for %dms" sleep-for))
                              (Thread/sleep (* 1000 sleep-for))
                              (get-car-state car))
              [val ch] (alts! [kill-ch (go
                                         (log/info log-prefix (format "Sleeping for %dms" sleep-for))
                                         (Thread/sleep (* 1000 sleep-for))
                                         (get-car-state car))])]
          (if (= ch kill-ch)
            (log/info log-prefix "Process dying...")
            (let [{err :err car-state :car-state} val]
              (if (some? err)
                (do
                  (log/error log-prefix (format "Failed to get car state; %s" (ex-message err)))
                  (recur 10 last-car-state))
                (cond

                  (nil? last-car-state)
                  (do
                    (log/info log-prefix "Received first car state")
                    (>! output-ch car-state)
                    (recur 30 car-state))

                  (not (is-car-state-newer? car-state last-car-state))
                  (do
                    (log/info log-prefix "No new car state available")
                    (recur 30 last-car-state))

                  :else
                  (do
                    (log/info log-prefix "Received new car state")
                    (log/info log-prefix (make-car-state-message car car-state))
                    (>! output-ch car-state)
                    (recur 30 car-state))))))))

      (log/verbose log-prefix "Closing channel...")
      (close! output-ch)
      (log/verbose log-prefix "Process died"))
    output-ch))

