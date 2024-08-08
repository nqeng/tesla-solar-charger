(ns tesla-solar-charger.recorder.recorder)

(defprotocol IRecorder
  (record-data [recorder location ?car-state ?data-point]))
