(ns tesla-solar-charger.gophers.get-car-state
  (:require
   [taoensso.timbre :as timbre]
   [tesla-solar-charger.car-data-source.car-data-source :refer [get-latest-car-state]]
   [clojure.core.async :refer [>! close! timeout alts! chan go]]))

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
  [data-source output-ch kill-ch log-prefix]
  (letfn [(info [msg] (timbre/info (format "[%s]" log-prefix) msg))
          (error [msg] (timbre/error (format "[%s]" log-prefix) msg))
          (debug [msg] (timbre/debug (format "[%s]" log-prefix) msg))]
    (go
      (info "Process starting...")
      (loop [data-source data-source
             sleep-for 0
             last-car-state nil]
        (let [timeout-ch (timeout (* 1000 sleep-for))
              [val ch] (alts! [kill-ch timeout-ch])]
          (if (= ch kill-ch)
            (info "Process dying...")
            (let [result-ch (go (get-latest-car-state data-source))
                  [val ch] (alts! [kill-ch result-ch])]
              (if (= ch kill-ch)
                (info "Process dying...")
                (let [{err :err car-state :val data-source :obj} val]
                  (if (some? err)
                    (do
                      (error (format "Failed to fetch car state; %s" (ex-message err)))
                      (recur data-source 30 last-car-state))
                    (if (and (some? last-car-state)
                             (not (is-car-state-newer? car-state last-car-state)))
                      (do
                        (info "No new car state; sleeping for 30s")
                        (recur data-source 30 last-car-state))
                      (do
                        (info (make-car-state-message car-state))
                        (debug "Putting value on channel...")
                        (>! output-ch car-state)
                        (debug "Put value on channel")
                        (recur data-source 30 car-state))))))))))
      (info "Process died"))))

