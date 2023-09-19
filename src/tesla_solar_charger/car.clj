(ns tesla-solar-charger.car)

(defprotocol Car
  (get-vin [car])
  (get-state [car])
  (set-charge-rate [car new-charge-rate-amps])
  (set-charge-limit [car new-charge-limit-percent])
  (restore-state [car state]))

(defprotocol CarState
  (is-charging? [state])
  (is-override-active? [state])
  (get-charge-rate-amps [state])
  (get-charge-limit-percent [state])
  (get-max-charge-rate-amps [state])
  (get-latitude [state])
  (get-longitude [state])
  (will-reach-target-by? [state target-time]))
