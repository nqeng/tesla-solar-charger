(ns tesla-solar-charger.regulator.regulator)

(defprotocol IRegulator
  (make-regulation-from-new-car-state [regulator new-car-state])
  (make-regulation-from-new-data-point [regulator new-data-point]))

(defn make-regulation
  [new-charge-power-watts message]
  {:new-charge-power-watts new-charge-power-watts
   :message message})
