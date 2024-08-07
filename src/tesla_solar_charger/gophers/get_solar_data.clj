(ns tesla-solar-charger.gophers.get-solar-data
  (:require
   [tesla-solar-charger.log :as log]
   [taoensso.timbre :as timbre]
   [tesla-solar-charger.solar-data-source.solar-data-source :refer [get-latest-data-point]]
   [clojure.core.async :refer [>! alts! timeout chan go close!]]))

(defn is-data-point-newer?
  [data-point1 data-point2]
  (.isAfter (:timestamp data-point1) (:timestamp data-point2)))

(defn make-data-point-message
  [data-point]
  (format
   "Excess power is %.2fW as of %s"
   (:excess-power-watts data-point) (:timestamp data-point)))

(defn fetch-new-solar-data
  [data-source output-ch kill-ch log-prefix]
  (letfn [(info [msg] (timbre/info (format "[%s]" log-prefix) msg))
          (error [msg] (timbre/error (format "[%s]" log-prefix) msg))
          (debug [msg] (timbre/debug (format "[%s]" log-prefix) msg))]
    (go
      (info "Process starting...")
      (loop [data-source data-source
             sleep-for 0
             last-data-point nil]
        (let [timeout-ch (timeout (* 1000 sleep-for))
              [val ch] (alts! [kill-ch timeout-ch])]
          (if (= ch kill-ch)
            (info "Process dying...")
            (let [result-ch (go (get-latest-data-point data-source))
                  [val ch] (alts! [kill-ch result-ch])]
              (if (= ch kill-ch)
                (info "Process dying...")
                (let [{err :err data-source :obj data-point :val} val]
                  (if (some? err)
                    (do
                      (error (format "Failed to fetch solar data; %s" (ex-message err)))
                      (recur data-source 30 last-data-point))
                    (if (and (some? last-data-point)
                             (not (is-data-point-newer? data-point last-data-point)))
                      (do
                        (info "No new solar data")
                        (recur data-source 30 last-data-point))
                      (do
                        (info (make-data-point-message data-point))
                        (debug "Putting value on channel...")
                        (>! output-ch data-point)
                        (debug "Put value on channel")
                        (recur data-source 30 data-point))))))))))
      (info "Process died"))))

