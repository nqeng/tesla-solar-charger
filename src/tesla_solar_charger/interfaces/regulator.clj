(ns tesla-solar-charger.interfaces.regulator)

(declare Regulator)
(declare Regulation)
(declare get-car)
(declare get-site)
(declare set-first-successful-regulation)
(declare set-last-successful-regulation)
(declare set-last-attempted-regulation)

(defprotocol Regulation
  (get-car-state [regulation])
  (get-site-data [regulation])
  (get-car-state-to-restore [regulation])
  (used-solar-data? [regulation])
  (used-this-solar-data? [regulation this-site-data])
  (did-car-start-charging? [regulation])
  (did-car-stop-charging? [regulation])
  (set-used-solar-data [regulation used-solar-data?])
  (set-did-car-start-charging [regulation did-car-start-charging?])
  (set-did-car-stop-charging [regulation did-car-stop-charging?])
  (set-charge-rate-amps [regulation charge-rate-amps])
  (set-car-state-to-restore [regulation car-state])
  (set-status-message [regulation message])
  (get-status-message [regulation])
  (log-status-message [regulation])
  (apply-this [regulation car site]))

(defprotocol Regulator
  (get-site [regulator])
  (get-car [regulator])
  (get-first-successful-regulation [regulator])
  (get-last-successful-regulation [regulator])
  (get-last-attempted-regulation [regulator])
  (set-first-successful-regulation [regulator regulation])
  (set-last-successful-regulation [regulator regulation])
  (set-last-attempted-regulation [regulator regulation])
  (with-regulation-creater [regulator regulation-creater])
  (get-regulation-creater [regulator])
  (create-regulation [regulator])
  (apply-regulation [regulator regulation])
  (regulate [regulator car-state site-data]))

(defprotocol RegulationCreater
  (create-regulation [regulation-creater
                      car
                      site
                      car-state
                      site-data
                      first-successful-regulation
                      last-successful-regulation
                      last-attempted-regulation
                      settings]))

