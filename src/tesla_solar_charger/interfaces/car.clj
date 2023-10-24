(ns tesla-solar-charger.interfaces.car)

(defprotocol Car
  (get-vin [car])
  (get-name [car])
  (get-state [car])
  (set-charge-rate [car new-charge-rate-amps])
  (restore-state [car state]))

(defprotocol CarState
  (get-time [state])
  (get-id [state])
  (is-charging? [state])
  (is-override-active? [state])
  (get-charge-rate-amps [state])
  (get-charge-limit-percent [state])
  (get-battery-level-percent [state])
  (get-charger-power-kilowatts [state])
  (get-minutes-to-target-percent [state target-percent])
  (get-minutes-to-target-percent-at-max-rate [state target-percent])
  (should-override-to-reach-target? [state target-percent target-time])
  (get-minutes-to-full-charge [state])
  (get-max-charge-rate-amps [state])
  (get-latitude [state])
  (get-longitude [state])
  (will-reach-target-by? [state target-percent target-time]))


