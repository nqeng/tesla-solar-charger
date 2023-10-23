(ns tesla-solar-charger.implementations.simple-regulation-creater
  (:require
   [tesla-solar-charger.interfaces.car :as car]
   [better-cond.core :refer [cond] :rename {cond better-cond}]
   [tesla-solar-charger.interfaces.site :as site]
   [tesla-solar-charger.interfaces.regulator :as regulator]))

(defn did-car-start-charging-here?
  [current-state previous-regulation site]
  (and
   (some? current-state)
   (site/is-car-here? site current-state)
   (car/is-charging? current-state)
   (or
    (nil? previous-regulation)
    (not (site/is-car-here? site (regulator/get-car-state previous-regulation)))
    (not (car/is-charging? (regulator/get-car-state previous-regulation))))))

(defn did-car-stop-charging-here?
  [current-state previous-regulation site]
  (and
   (some? previous-regulation)
   (some? (regulator/get-car-state previous-regulation))
   (car/is-charging? (regulator/get-car-state previous-regulation))
   (site/is-car-here? site (regulator/get-car-state previous-regulation))
   (or
    (not (car/is-charging? current-state))
    (not (site/is-car-here? site current-state)))))

(defn limit
  [num min max]
  (cond
    (> num max) max
    (< num min) min
    :else       num))

(defrecord SimpleRegulationCreater [power-buffer-watts max-climb-amps max-drop-amps]
  regulator/RegulationCreater
  (create-regulation [regulation-creater regulator car-state site-data]
    (let [regulation (regulator/->ChargeRateRegulation car-state site-data)
          last-attempted-regulation (regulator/get-last-attempted-regulation regulator)
          last-successful-regulation (regulator/get-last-successful-regulation regulator)
          first-successful-regulation (regulator/get-first-successful-regulation regulator)
          site (regulator/get-site regulator)]
      (better-cond
       (and
        (some? last-attempted-regulation)
        (= (car/get-id car-state)
           (car/get-id (regulator/get-car-state last-attempted-regulation))))
       (-> regulation
           (regulator/add-message (format "Already regulated this car state")))

       (did-car-start-charging-here? car-state last-attempted-regulation site)
       (-> regulation
           (regulator/set-did-car-start-charging true)
           (regulator/set-charge-rate-amps 0)
           (regulator/add-message (format "Car started charging")))

       (did-car-stop-charging-here? car-state last-attempted-regulation site)
       (let [charge-rate-amps (car/get-charge-rate-amps (regulator/get-car-state first-successful-regulation))]
         (-> regulation
             (regulator/set-did-car-stop-charging true)
             (regulator/set-charge-rate-amps charge-rate-amps)
             (regulator/add-message (format "Car stopped charging"))))

       (not (site/is-car-here? site car-state))
       (-> regulation
           (regulator/add-message (format "Car is not at %s" (site/get-name site))))

       (not (car/is-charging? car-state))
       (-> regulation
           (regulator/add-message "Car is not charging"))

       (car/is-override-active? car-state)
       (-> regulation
           (regulator/set-charge-rate-amps (car/get-max-charge-rate-amps car-state))
           (regulator/add-message "Override active"))

       (nil? site-data)
       (-> regulation
           (regulator/add-message "No solar data"))

       :let [data-point (last (site/get-points site-data))]

       :do (println (format "last successful regulation: %s" last-successful-regulation))

       (and
        (true? (regulator/used-solar-data? last-successful-regulation))
        (= (site/get-time data-point)
           (site/get-time (last (site/get-points (regulator/get-site-data last-successful-regulation))))))
       (-> regulation
           (regulator/set-used-solar-data true)
           (regulator/add-message "Already regulated this solar data"))
       (nil? (site/get-excess-power-watts (last (site/get-points site-data))))
       (-> regulation
           (regulator/add-message "No excess power"))

       :else
       (let [excess-power (site/get-excess-power-watts data-point)
             available-power-watts (- excess-power power-buffer-watts)
             current-rate-amps (car/get-charge-rate-amps car-state)
             max-charge-rate-amps (car/get-max-charge-rate-amps car-state)
             adjustment-rate-amps (site/power-watts-to-current-amps site available-power-watts)
             adjustment-rate-amps (limit adjustment-rate-amps (- max-drop-amps) max-climb-amps)
             new-charge-rate-amps (-> current-rate-amps
                                      (+ adjustment-rate-amps)
                                      float
                                      Math/round
                                      int
                                      (limit 0 max-charge-rate-amps))]
         (-> regulation
             (regulator/set-used-solar-data true)
             (regulator/set-charge-rate-amps new-charge-rate-amps)
             (regulator/add-message (format "Excess power is %.2fW (%.2fW available)"
                                            (float excess-power)
                                            (float available-power-watts)))
             (regulator/add-message (format "Altering charge rate to %sA (%s%s from %sA)"
                                            new-charge-rate-amps
                                            (if (neg? adjustment-rate-amps) "-" "+")
                                            (abs (int adjustment-rate-amps))
                                            current-rate-amps))))))))


