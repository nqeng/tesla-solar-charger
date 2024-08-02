(ns tesla-solar-charger.car-charge-setter.car-charge-setter)

(defprotocol ICarChargeSetter
  (set-charge-power [charge-setter charge-power-watts]))
