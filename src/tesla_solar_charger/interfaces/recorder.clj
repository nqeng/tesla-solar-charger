(ns tesla-solar-charger.interfaces.recorder)



(defprotocol Recorder
  (record-data [recorder car-state site-data])
  )
