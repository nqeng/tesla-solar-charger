(ns tesla-solar-charger.gophers.regulate-charge-rate
  (:require
   [tesla-solar-charger.log :as log]
   [tesla-solar-charger.haversine :refer [haversine]]
   [clojure.core.async :as async :refer [alts! >! go]]
   [tesla-solar-charger.utils :as utils]))

(def distance-between-geo-points-kilometers haversine)

(defn distance-between-geo-points-meters
  [lat1 lng1 lat2 lng2]
  (* 1000 (distance-between-geo-points-kilometers {:lng lng1 :lat lat1} {:lng lng2 :lat lat2})))

(defn is-car-at-site?
  [site car-state]
  (let [range-meters 50
        distance-between-meters (distance-between-geo-points-meters
                                 (:latitude car-state)
                                 (:longitude car-state)
                                 (:latitude site)
                                 (:longitude site))]
    (< distance-between-meters range-meters)))

(defn calc-new-charge-power-watts
  [power-watts excess-power-watts power-buffer-watts max-climb-watts max-drop-watts]
  (let [available-power-watts (- excess-power-watts power-buffer-watts)
        adjustment-power-watts (utils/clamp-min-max available-power-watts (- max-drop-watts) max-climb-watts)
        new-power-watts (+ power-watts adjustment-power-watts)]
    new-power-watts))

(defn is-car-charging-at-site?
  [site car-state]
  (and (is-car-at-site? site car-state) (:is-charging car-state)))

(defn did-car-start-charging?
  [car-state last-car-state]
  (and (:is-charging car-state) (or (nil? last-car-state) (not (:is-charging last-car-state)))))

(defn did-car-leave-site?
  [site car-state last-car-state]
  (and (some? last-car-state) (not (is-car-at-site? site car-state)) (is-car-at-site? site last-car-state)))

(defn did-car-enter-site?
  [site car-state last-car-state]
  (and (is-car-at-site? site car-state) (or (nil? last-car-state) (not (is-car-at-site? site last-car-state)))))

(defn did-car-stop-charging?
  [car-state last-car-state]
  (and (some? last-car-state) (not (:is-charging car-state)) (:is-charging last-car-state)))

(defn did-override-turn-off?
  [car-state last-car-state]
  (and (some? last-car-state) (not (:is-override-active car-state)) (:is-override-active last-car-state)))

(defn did-override-turn-on?
  [car-state last-car-state]
  (and (:is-override-active car-state) (or (nil? last-car-state) (not (:is-override-active last-car-state)))))

(defn regulate-charge-rate
  [site car-state-ch site-data-ch charge-current-ch err-ch kill-ch]
  (let [log-prefix "regulate-charge-rate"]
    (go
      (log/info log-prefix "Process starting...")
      (loop [state {:last-car-state nil
                    :last-data-point nil}]
        (let [[_ ch] (alts! [kill-ch] :default nil)]
          (if (= ch kill-ch)
            (log/info log-prefix "Process dying...")
            (let [[val ch] (alts! [car-state-ch site-data-ch])]
              (if (nil? val)
                (do
                  (log/error log-prefix "Input channel was closed")
                  (>! err-ch (ex-info "Input channel was closed" {:type :attribute-nil})))
                (if (= ch car-state-ch)
                  (do
                    (log/info log-prefix "Received car state")
                    (let [car-state val
                          last-car-state (:last-car-state state)
                          state (assoc state :last-car-state car-state)
                          max-current-amps (:max-charge-current-amps car-state)]
                      (cond
                        (and (did-car-stop-charging? car-state last-car-state)
                             (did-car-leave-site? site car-state last-car-state))
                        (do (log/info log-prefix "Car stopped charging and left")
                            (>! charge-current-ch max-current-amps))

                        (did-car-stop-charging? car-state last-car-state)
                        (do
                          (log/info log-prefix "Car stopped charging")
                          (>! charge-current-ch max-current-amps))

                        (did-car-leave-site? site car-state last-car-state)
                        (log/info log-prefix "Car left")

                        (and (did-car-enter-site? site car-state last-car-state)
                             (did-car-start-charging? car-state last-car-state))
                        (do
                          (log/info log-prefix "Car entered and started charging")
                          (if (:is-override-active car-state)
                            
                            (>! charge-current-ch 0)))

                        (did-car-enter-site? site car-state last-car-state)
                        (log/info log-prefix "Car entered")

                        (did-car-start-charging? car-state last-car-state)
                        (do
                          (log/info log-prefix "Car started charging")
                          (if (:is-override-active car-state)
                            (>! charge-current-ch max-current-amps)
                            (>! charge-current-ch 0)))

                        (did-override-turn-on? car-state last-car-state)
                        (do
                          (log/info log-prefix "Override turned on")
                          (>! charge-current-ch max-current-amps))

                        (did-override-turn-off? car-state last-car-state)
                        (do
                          (log/info log-prefix "Override turned off")
                          (>! charge-current-ch 0))

                        :else
                        (log/info log-prefix "No change to car state"))

                      (recur state)))

                  (do
                    (log/info log-prefix "Received site data")
                    (let [data-point val
                          last-car-state (:last-car-state state)
                          last-data-point (:last-data-point state)
                          state (assoc state :last-data-point data-point)]
                      (cond
                        (and (some? last-data-point)
                             (= (:excess-power-watts data-point) (:excess-power-watts last-data-point)))
                        (log/info log-prefix "No change to excess power")

                        (nil? last-car-state)
                        (log/info log-prefix "No car state")

                        (not (is-car-charging-at-site? site last-car-state))
                        (log/info log-prefix "Car is not charging at this site")

                        (:is-override-active last-car-state)
                        (log/info log-prefix "Override active")

                        :else
                        (let [charge-current-amps (:charge-current-amps last-car-state)
                              charge-power-watts (utils/amps-to-watts-three-phase-australia charge-current-amps)
                              excess-power-watts (:excess-power-watts data-point)
                              new-charge-power-watts (calc-new-charge-power-watts charge-power-watts excess-power-watts 0 16 16)]
                          (>! charge-current-ch new-charge-power-watts)))

                      (recur state)))))))))
      (log/info log-prefix "Process died"))))