(ns tesla-solar-charger.recorder.recorder)

(defprotocol IRecorder
  (record-data recorder car-state data-point))
