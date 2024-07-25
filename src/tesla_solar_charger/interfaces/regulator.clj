(ns tesla-solar-charger.interfaces.regulator)

(defprotocol IRegulator
  (regulate-new-car-state [regulator car-state last-car-state latest-data-point])
  (regulate-new-data-point [regulator data-point last-data-point latest-car-state]))

(defn make-regulation
  [new-power-watts should-set-charge-rate message]
  {:new-charge-power-watts new-power-watts
   :should-set-charge-rate should-set-charge-rate
   :message message})

#_(if (nil? last-data-point)
    (log/info log-prefix "Received first solar data")
    (log/info log-prefix "Received solar data"))
#_(cond
    (and (some? last-data-point)
         (= (:excess-power-watts data-point) (:excess-power-watts last-data-point)))
    (log/info log-prefix "No change to excess power")

    (nil? last-car-state)
    (log/info log-prefix "No car state")

    (not (is-car-charging-at-location? location last-car-state))
    (log/info log-prefix "Car is not charging at this location")

    (:is-override-active last-car-state)
    (log/info log-prefix "Override active")

    :else
    (let [charge-power-watts (charger/get-car-charge-power-watts charger last-car-state)
          excess-power-watts (:excess-power-watts data-point)
          new-charge-power-watts (calc-new-charge-power-watts charge-power-watts excess-power-watts 0 16 16)]
      (>! output-ch new-charge-power-watts)))

#_(if (nil? last-car-state)
    (log/info log-prefix "Received first car state")
    (log/info log-prefix "Received car state"))
#_(cond
    (nil? last-car-state)
    (log/info "No previous car state")

    (and (did-car-stop-charging? car-state last-car-state)
         (did-car-leave-location? location car-state last-car-state))
    (do (log/info log-prefix "Car stopped charging and left")
        (>! output-ch max-charge-power-watts))

    (did-car-stop-charging? car-state last-car-state)
    (do
      (log/info log-prefix "Car stopped charging")
      (>! output-ch max-charge-power-watts))

    (did-car-leave-location? location car-state last-car-state)
    (log/info log-prefix "Car left")

    (and (did-car-enter-location? location car-state last-car-state)
         (did-car-start-charging? car-state last-car-state))
    (do
      (log/info log-prefix "Car entered and started charging")
      (if (:is-override-active car-state)
        (>! output-ch max-charge-power-watts)
        (>! output-ch 0)))

    (did-car-enter-location? location car-state last-car-state)
    (log/info log-prefix "Car entered")

    (did-car-start-charging? car-state last-car-state)
    (do
      (log/info log-prefix "Car started charging")
      (if (:is-override-active car-state)
        (>! output-ch max-charge-power-watts)
        (>! output-ch 0)))

    (did-override-turn-on? car-state last-car-state)
    (do
      (log/info log-prefix "Override turned on")
      (>! output-ch max-charge-power-watts))

    (did-override-turn-off? car-state last-car-state)
    (do
      (log/info log-prefix "Override turned off")
      (>! output-ch 0))

    :else
    (log/info log-prefix (make-car-state-message car car-state)))
