(ns tesla-solar-charger.interfaces.car)

(defprotocol Car
  (get-vin [car])
  (get-name [car])
  (get-state [car])
  (set-charge-current [car new-charge-rate-amps])
  (restore-this-state [car state-to-restore]))

(defprotocol CarState
  (get-time [state])
  (get-id [state])
  (is-newer? [state other-state])
  (is-charging? [state])
  (is-connected? [state])
  (is-override-active? [state])
  (get-charge-current-amps [state])
  (get-charge-limit-percent [state])
  (get-minutes-to-target-percent [state target-percent])
  (get-minutes-to-target-percent-at-max-rate [state target-percent])
  (should-override-to-reach-target? [state target-percent target-time])
  (get-minutes-to-full-charge [state])
  (get-max-charge-current-amps [state])
  (get-latitude [state])
  (get-longitude [state])
  (will-reach-target-by? [state target-percent target-time]))


