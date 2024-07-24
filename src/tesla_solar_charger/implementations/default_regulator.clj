(ns tesla-solar-charger.implementations.default-regulator
  (:require
   [tesla-solar-charger.interfaces.regulator :as regulator]))

(defrecord DefaultRegulator [car site regulation-creater]

  regulator/Regulator

  (get-car [regulator] car)
  (get-site [regulator] site)
  (get-first-successful-regulation [regulator] (:first-successful-regulation regulator))
  (get-last-attempted-regulation [regulator] (:last-attempted-regulation regulator))
  (get-last-successful-regulation [regulator] (:last-successful-regulation regulator))
  (set-first-successful-regulation [regulator regulation]
    (assoc regulator :first-successful-regulation regulation))
  (set-last-attempted-regulation [regulator regulation]
    (assoc regulator :last-attempted-regulation regulation))
  (set-last-successful-regulation [regulator regulation]
    (assoc regulator :last-successful-regulation regulation))
  (regulate [regulator car-state site-data log-chan log-prefix]
    (let [regulation (-> regulation-creater
                         (regulator/create-regulation regulator car-state site-data))
          regulator (-> regulation
                        (regulator/apply-regulation regulator log-chan log-prefix))]
      regulator)))
