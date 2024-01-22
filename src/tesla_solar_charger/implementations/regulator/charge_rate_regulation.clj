(ns tesla-solar-charger.implementations.regulator.charge-rate-regulation
  (:require
   [tesla-solar-charger.utils :as utils]
   [tesla-solar-charger.log :as log]
   [tesla-solar-charger.interfaces.site :as Isite]
   [tesla-solar-charger.interfaces.regulator :as Iregulator]
   [tesla-solar-charger.interfaces.site-data :as Isite-data]
   [tesla-solar-charger.interfaces.car :as Icar]
   [tesla-solar-charger.implementations.regulator.charge-rate-regulation :as charge-rate-regulation]
   [tesla-solar-charger.interfaces.site-charger :as Isite-charger]))

(defn get-charge-power-adjustment-watts
  [regulation]
  (utils/throw-if-attribute-nil regulation :charge-power-adjustment-watts)
  (:charge-power-adjustment-watts regulation))

(defrecord ChargeRateRegulation [car-state site-data]

  Iregulator/Regulation

  (get-car-state [regulation] car-state)
  (get-site-data [regulation] site-data)
  (get-car-state-to-restore [regulation] (:car-state-to-restore regulation))
  (used-solar-data? [regulation] (true? (:used-solar-data? regulation)))
  (used-this-solar-data? [regulation this-site-data]
    (cond
      (not (Iregulator/used-solar-data? regulation))
      false

      (nil? (Iregulator/get-site-data regulation))
      false

      :else
      (= (Isite-data/get-latest-time (Iregulator/get-site-data regulation))
         (Isite-data/get-latest-time this-site-data))))
  (did-car-start-charging? [regulation] (true? (:did-car-start-charging? regulation)))
  (did-car-stop-charging? [regulation] (true? (:did-car-stop-charging? regulation)))
  (set-used-solar-data [regulation used-solar-data?] (assoc regulation :used-solar-data? used-solar-data?))
  (set-did-car-start-charging [regulation did-car-start-charging?] (assoc regulation :did-car-start-charging? did-car-start-charging?))
  (set-did-car-stop-charging [regulation did-car-stop-charging?] (assoc regulation :did-car-stop-charging? did-car-stop-charging?))
  (set-car-state-to-restore [regulation car-state] (assoc regulation :car-state-to-restore car-state))
  (set-status-message [regulation message] (update regulation :message #(conj % message)))
  (get-status-message [regulation] (:message regulation))
  (log-status-message [regulation] (doseq [msg (Iregulator/get-status-message regulation)] (log/info msg)))
  (apply-this [regulation car site]
    (let [charger (Isite/get-charger site)]
      (try
        (when-some [car-state (Iregulator/get-car-state-to-restore regulation)]
          (Icar/restore-this-state car car-state)
          (log/info (format "Restored car state to %s" car-state)))
        (when-some [charge-power-adjustment-watts (get-charge-power-adjustment-watts regulation)]
          (Isite-charger/adjust-car-charge-power charger car charge-power-adjustment-watts)
          (log/info (format "Adjusted charge rate by %s%sW"
                            (if (neg? charge-power-adjustment-watts) "-" "+")
                            charge-power-adjustment-watts)))
        (catch clojure.lang.ExceptionInfo e
          (throw e))
        (catch Exception e
          (throw e))))))

(defn new-ChargeRateRegulation
  [car-state site-data]
  (let [the-map {:car-state car-state
                 :site-data site-data}
        defaults {:message nil}
        regulation (map->ChargeRateRegulation (merge defaults the-map))]
    regulation))

(defn set-charge-power-adjustment-watts
  [regulation charge-power-adjustment-watts]
  (assoc regulation :charge-power-adjustment-watts charge-power-adjustment-watts))

(defn set-charge-power-watts
  [regulation charge-power-watts]
  (assoc regulation :charge-power-watts charge-power-watts))

(defn setup-from-excess-power-watts
  [regulation excess-power-watts power-buffer-watts max-climb-watts max-drop-watts]
  (let [available-power-watts (- excess-power-watts power-buffer-watts)
        available-power-watts (utils/clamp-min-max available-power-watts (- max-drop-watts) max-climb-watts)
        regulation (set-charge-power-adjustment-watts regulation available-power-watts)
        status-message (format "Excess power is %.2fW (%.2fW available)%nAdjusting charge power by %s%.2fW"
                               (float excess-power-watts)
                               (float available-power-watts)
                               (if (neg? available-power-watts) "-" "+")
                               (float (abs available-power-watts)))
        regulation (Iregulator/set-status-message regulation status-message)
        regulation (Iregulator/set-used-solar-data regulation true)]
    regulation))
