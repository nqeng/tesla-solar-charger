(ns tesla-solar-charger.implementations.regulator.target-time-regulation-creater
  (:require
   [tesla-solar-charger.interfaces.car :as Icar]
   [tesla-solar-charger.interfaces.site :as Isite]
   [tesla-solar-charger.interfaces.site-charger :as Isite-charger]
   [tesla-solar-charger.interfaces.site-data :as Isite-data]
   [tesla-solar-charger.interfaces.regulator :as Iregulator]
   [tesla-solar-charger.implementations.regulator.charge-rate-regulation :as charge-rate-regulation]
   [tesla-solar-charger.utils :as utils]))

(def defaults
  {"starting_charge_rate_watts" 0
   "target_hour" 17
   "target_minute" 0
   "target_second" 0
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

(defn get-target-time-setting
  [settings]
  (let [target-hour (get-setting-or-default settings "target_hour")
        target-minute (get-setting-or-default settings "target_minute")
        target-second (get-setting-or-default settings "target_second")
        target-time (if (and
                         (some? target-hour)
                         (some? target-minute)
                         (some? target-second))
                      (-> (utils/local-now)
                          (.withHour target-hour)
                          (.withMinute target-minute)
                          (.withSecond target-second)
                          (.withNano 0))
                      nil)]
    target-time))

(defrecord TargetTimeRegulationCreater []

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
    (let [car-name (Icar/get-name car)
          site-name (Isite/get-name site)
          charger (Isite/get-charger site)
          starting-charge-rate-watts (get-setting-or-default settings "starting_charge_rate_watts")
          max-charge-power-watts (Isite-charger/get-car-max-charge-power-watts charger car-state)
          target-time (get-target-time-setting settings)
          target-percent (get-setting-or-default settings "target_percent" (Icar/get-charge-limit-percent car-state))
          max-climb-watts (get-setting-or-default settings "max_climb_watts" max-charge-power-watts)
          max-drop-watts (get-setting-or-default settings "max_drop_watts" max-charge-power-watts)
          power-buffer-watts (get-setting-or-default settings "power_buffer_watts")
          regulation (charge-rate-regulation/new-ChargeRateRegulation car-state site-data)]
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

        (Icar/should-override-to-reach-target? car-state target-percent target-time)
        (-> regulation
            (Iregulator/set-charge-rate-amps (Icar/get-max-charge-current-amps car-state))
            (Iregulator/set-status-message (format "Overriding to reach %s%% by %s"
                                                   target-percent
                                                   (utils/format-local-time "HH:mm:ss" target-time))))

        (nil? site-data)
        (-> regulation
            (Iregulator/set-status-message "No solar data"))

        (Iregulator/used-this-solar-data? last-successful-regulation site-data)
        (-> regulation
            (Iregulator/set-used-solar-data true)
            (Iregulator/set-status-message "Already regulated this solar data"))

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

(defn new-TargetTimeRegulationCreater
  []
  (let [the-map {}
        defaults {}]
    (map->TargetTimeRegulationCreater (merge defaults the-map))))

