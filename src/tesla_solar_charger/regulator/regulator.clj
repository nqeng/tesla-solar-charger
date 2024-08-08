(ns tesla-solar-charger.regulator.regulator)

(defprotocol IRegulator
  (regulate-new-car-state [regulator new-car-state])
  (regulate-new-data-point [regulator new-data-point]))

