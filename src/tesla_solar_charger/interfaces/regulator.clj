(ns tesla-solar-charger.interfaces.regulator
  (:require
   [tesla-solar-charger.interfaces.car :as car]
   [clojure.core.async :as async]
   [tesla-solar-charger.interfaces.site :as site]))

(declare Regulator)
(declare Regulation)
(declare get-car)
(declare set-first-successful-regulation)
(declare set-last-successful-regulation)
(declare set-last-attempted-regulation)

(defprotocol Regulation
  (get-car-state [regulation])
  (get-site-data [regulation])
  (get-charge-rate-amps [regulation])
  (get-charge-limit-percent [regulation])
  (used-solar-data? [regulation])
  (did-car-start-charging? [regulation])
  (did-car-stop-charging? [regulation])
  (set-used-solar-data [regulation used-solar-data?])
  (set-did-car-start-charging [regulation did-car-start-charging?])
  (set-did-car-stop-charging [regulation did-car-stop-charging?])
  (set-charge-rate-amps [regulation charge-rate-amps])
  (set-charge-limit-percent [regulation charge-limit-percent])
  (add-message [regulation message])
  (get-messages [regulation])
  (apply-regulation [regulation car log-chan log-prefix]))

(defrecord ChargeRateChargeLimitRegulation [car-state site-data]

  Regulation

  (get-car-state [regulation] car-state)
  (get-site-data [regulation] site-data)
  (get-charge-rate-amps [regulation] (:charge-rate-amps regulation))
  (get-charge-limit-percent [regulation] (:charge-limit-percent regulation))
  (used-solar-data? [regulation] (:used-solar-data? regulation))
  (did-car-start-charging? [regulation] (:did-car-start-charging? regulation))
  (did-car-stop-charging? [regulation] (:did-car-stop-charging? regulation))
  (set-used-solar-data [regulation used-solar-data?]
    (assoc regulation :used-solar-data? used-solar-data?))
  (set-did-car-start-charging [regulation did-car-start-charging?]
    (assoc regulation :did-car-start-charging? did-car-start-charging?))
  (set-did-car-stop-charging [regulation did-car-stop-charging?]
    (assoc regulation :did-car-stop-charging? did-car-stop-charging?))
  (set-charge-rate-amps [regulation charge-rate-amps]
    (assoc regulation :charge-rate-amps charge-rate-amps))
  (set-charge-limit-percent [regulation charge-limit-percent]
    (assoc regulation :charge-limit-percent charge-limit-percent))
  (add-message [regulation message]
    (update regulation :messages #(vec (conj % message))))
  (get-messages [regulation] (:messages regulation))
  (apply-regulation [regulation regulator log-chan log-prefix]
    (let [car (get-car regulator)
          regulator (set-last-attempted-regulation regulator regulation)]
      (try
        (doseq [message (get-messages regulation)] (async/>!! log-chan {:level :info :prefix log-prefix :message message}))
        (when (some? (get-charge-rate-amps regulation))
          (car/set-charge-rate car (get-charge-rate-amps regulation))
          (async/>!! log-chan {:level :info :prefix log-prefix :message (format "Set charge rate to %sA" (get-charge-rate-amps regulation))}))
        (when (some? (get-charge-limit-percent regulation))
          (car/set-charge-limit car (get-charge-limit-percent regulation))
          (async/>!! log-chan {:level :info :prefix log-prefix :message (format "Set charge limit to %s%%" (get-charge-limit-percent regulation))}))

        (let [regulator (set-last-successful-regulation regulator regulation)
              regulator (if (did-car-start-charging? regulation)
                          (set-first-successful-regulation regulator regulation)
                          regulator)
              regulator (if (did-car-stop-charging? regulation)
                          (set-first-successful-regulation regulator nil)
                          regulator)]
          regulator)

        (catch clojure.lang.ExceptionInfo e
          (throw e))
        (catch Exception e
          (throw e))))))

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
  (regulate [regulator car-state site-data log-chan log-prefix]))

(defprotocol RegulationCreater
  (create-regulation [regulation-creater regulator car-state site-data]))

