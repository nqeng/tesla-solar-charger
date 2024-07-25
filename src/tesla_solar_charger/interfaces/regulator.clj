(ns tesla-solar-charger.interfaces.regulator)

(defprotocol IRegulator
  (regulate-new-car-state [regulator car-state last-car-state latest-data-point])
  (regulate-new-data-point [regulator data-point last-data-point latest-car-state]))

(defn make-regulation
  [new-power-watts should-set-charge-rate message]
  {:new-power-watts new-power-watts
   :should-set-charge-rate should-set-charge-rate
   :message message})
