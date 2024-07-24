(ns tesla-solar-charger.implementations.regulator.simple-regulation-creater
  (:require
   [tesla-solar-charger.interfaces.car :as Icar]
   [tesla-solar-charger.interfaces.site :as Isite]
   [tesla-solar-charger.interfaces.site-charger :as Isite-charger]
   [tesla-solar-charger.interfaces.site-data :as Isite-data]
   [tesla-solar-charger.interfaces.regulator :as Iregulator]
   [tesla-solar-charger.implementations.regulator.charge-rate-regulation :as charge-rate-regulation]))

(def defaults
  {"starting_charge_rate_watts" 0
   "power_buffer_watts" 1000})

(defn get-setting-or-default
  ([settings key]
   (if (contains? settings key)
     (get settings key)
     (if (contains? defaults key)
       (get defaults key)
       (throw (ex-info (format "Default setting for %s was not defined" key) {:type :default-setting-not-defined})))))
  ([settings key default]
   (get settings key default)))

(defrecord SimpleRegulationCreater []

  Iregulator/RegulationCreater

  (create-regulation [regulation-creater
                      car
                      site
                      car-state
                      site-data
                      first-successful-regulation
                      last-successful-regulation
                      last-attempted-regulation
                      settings]
    (let [site-name (Isite/get-name site)
          charger (Isite/get-charger site)
          car-name (Icar/get-name car)
          regulation (charge-rate-regulation/new-ChargeRateRegulation car-state site-data)
          max-charge-power-watts (Isite-charger/get-car-max-charge-power-watts charger car-state)
          starting-charge-rate-watts (get-setting-or-default settings "starting_charge_rate_watts")
          max-climb-watts (get-setting-or-default settings "max_climb_watts" max-charge-power-watts)
          max-drop-watts (get-setting-or-default settings "max_drop_watts" max-charge-power-watts)
          power-buffer-watts (get-setting-or-default settings "power_buffer_watts")]
      (cond
        (not (Icar/is-newer? car-state (Iregulator/get-car-state last-attempted-regulation)))
        (-> regulation
            (Iregulator/set-status-message (format "Already regulated this car state")))

        (Isite/did-car-start-charging-here? site car-state (Iregulator/get-car-state last-successful-regulation))
        (-> regulation
            (Iregulator/set-did-car-start-charging true)
            (charge-rate-regulation/set-charge-power-watts starting-charge-rate-watts)
            (Iregulator/set-status-message (format "%s started charging" car-name)))

        (Isite/did-car-stop-charging-here? site car-state (Iregulator/get-car-state last-attempted-regulation))
        (let [initial-car-state (Iregulator/get-car-state first-successful-regulation)]
          (-> regulation
              (Iregulator/set-did-car-stop-charging true)
              (Iregulator/set-car-state-to-restore initial-car-state)
              (Iregulator/set-status-message (format "%s stopped charging" car-name))))

        (not (Isite/is-car-here? site car-state))
        (-> regulation
            (Iregulator/set-status-message (format "%s is not at %s" car-name site-name)))

        (not (Icar/is-charging? car-state))
        (-> regulation
            (Iregulator/set-status-message (format "%s is not charging" car-name)))

        (Icar/is-override-active? car-state)
        (-> regulation
            (Iregulator/set-charge-rate-amps (Icar/get-max-charge-current-amps car-state))
            (Iregulator/set-status-message "User override active"))

        (nil? site-data)
        (-> regulation
            (Iregulator/set-status-message "No solar data"))

        (Iregulator/used-this-solar-data? last-successful-regulation site-data)

        (-> regulation
            (Iregulator/set-used-solar-data true)
            (Iregulator/set-status-message "Already regulated this solar data point"))

        (nil? (Isite-data/get-latest-excess-power-watts site-data))
        (-> regulation
            (Iregulator/set-status-message "No excess power"))

        :else
        (let [excess-power-watts (Isite-data/get-latest-excess-power-watts site-data)
              regulation (charge-rate-regulation/setup-from-excess-power-watts
                          regulation
                          excess-power-watts
                          power-buffer-watts
                          max-climb-watts
                          max-drop-watts)]
          regulation)))))
