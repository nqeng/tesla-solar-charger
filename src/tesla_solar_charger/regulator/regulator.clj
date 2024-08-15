(ns tesla-solar-charger.regulator.regulator)

(defprotocol IRegulator
  (regulate-new-car-state [regulator new-car-state charge-setter options])
  (regulate-new-data-point [regulator new-data-point charge-setter options]))

