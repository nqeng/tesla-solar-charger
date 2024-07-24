(ns tesla-solar-charger.interfaces.site)

(defprotocol Site
  (is-car-here? [site car-state])
  (is-car-connected-here? [site car-state])
  (is-car-charging-here? [site car-state])
  (did-car-stop-charging-here? [site current-car-state previous-car-state])
  (did-car-start-charging-here? [site current-car-state previous-car-state])
  (did-car-connect-here? [site current-car-state previous-car-state])
  (did-car-disconnect-here? [site current-car-state previous-car-state])
  (did-car-enter-here? [site current-car-state previous-car-state])
  (did-car-leave-here? [site current-car-state previous-car-state])
  (with-data-source [site data-source])
  (get-latest-data-point [site])
  )


