(ns tesla-solar-charger.car-charge-setter.channel-charge-setter
  (:require
   [tesla-solar-charger.car-charge-setter.car-charge-setter :refer [ICarChargeSetter]]
   [clojure.core.async :refer [offer!]]
   ))

(defrecord ChannelChargeSetter 
  [ch]
  ICarChargeSetter
  (set-charge-power [charge-setter charge-power-watts]
    (offer! ch charge-power-watts)))

(defn new-ChannelChargeSetter
  [ch]
  (->ChannelChargeSetter ch))
