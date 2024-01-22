(ns tesla-solar-charger.implementations.regulator.default-regulator
  (:require
   [tesla-solar-charger.interfaces.regulator :as Iregulator]
   [tesla-solar-charger.interfaces.car :as Icar]
   [tesla-solar-charger.interfaces.site :as Isite]
   [clojure.core.async :as async]
   [tesla-solar-charger.utils :as utils]))

(defn get-settings-channel
  [regulator]
  (utils/throw-if-attribute-nil regulator :settings-channel)
  (:settings-channel regulator))

(defrecord DefaultRegulator []

  Iregulator/Regulator

  (get-car [regulator] (utils/throw-if-attribute-nil regulator :car) (:car regulator))
  (get-site [regulator] (utils/throw-if-attribute-nil regulator :site) (:site regulator))
  (get-first-successful-regulation [regulator] (:first-successful-regulation regulator))
  (get-last-attempted-regulation [regulator] (:last-attempted-regulation regulator))
  (get-last-successful-regulation [regulator] (:last-successful-regulation regulator))
  (set-first-successful-regulation [regulator regulation] (assoc regulator :first-successful-regulation regulation))
  (set-last-attempted-regulation [regulator regulation] (assoc regulator :last-attempted-regulation regulation))
  (set-last-successful-regulation [regulator regulation] (assoc regulator :last-successful-regulation regulation))
  (with-regulation-creater [regulator regulation-creater] (assoc regulator :regulation-creater regulation-creater))
  (get-regulation-creater [regulator] (utils/throw-if-attribute-nil regulator :regulation-creater) (:regulation-creater regulator))
  (apply-regulation [regulator regulation]
    (let [car (Iregulator/get-car regulator)
          site (Iregulator/get-site regulator)
          regulator (Iregulator/set-last-attempted-regulation regulator regulation)
          result (Iregulator/apply-this regulation car site)
          regulator (Iregulator/set-last-successful-regulation regulator regulation)
          regulator (if (Iregulator/did-car-start-charging? regulation)
                      (Iregulator/set-first-successful-regulation regulator regulation)
                      regulator)
          regulator (if (Iregulator/did-car-stop-charging? regulation)
                      (Iregulator/set-first-successful-regulation regulator nil)
                      regulator)]
      regulator))
  (regulate [regulator car-state site-data]
    (let [car (Iregulator/get-car regulator)
          site (Iregulator/get-site regulator)
          first-successful-regulation (Iregulator/get-first-successful-regulation regulator)
          last-successful-regulation (Iregulator/get-last-successful-regulation regulator)
          last-attempted-regulation (Iregulator/get-last-attempted-regulation regulator)
          regulation-creater (Iregulator/get-regulation-creater regulator)
          settings-ch (get-settings-channel regulator)
          settings (async/<!! settings-ch)
          regulator-settings (get settings (str (Isite/get-id site) (Icar/get-vin car)))
          regulation (Iregulator/create-regulation
                      regulation-creater
                      car
                      site
                      car-state
                      site-data
                      first-successful-regulation
                      last-successful-regulation
                      last-attempted-regulation
                      regulator-settings)
          _ (Iregulator/log-status-message regulation)
          regulator (Iregulator/apply-regulation regulator regulation)]
      regulator)))

(defn new-DefaultRegulator
  [car site settings-channel]
  (let [the-map {:car car
                 :site site
                 :settings-channel settings-channel}
        defaults {:first-successful-regulation nil
                  :last-successful-regulation nil
                  :last-attempted-regulation nil}
        regulator (map->DefaultRegulator (merge defaults the-map))]
    regulator))
