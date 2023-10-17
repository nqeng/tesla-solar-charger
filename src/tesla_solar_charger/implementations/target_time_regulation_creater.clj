(ns tesla-solar-charger.implementations.target-time-regulation-creater
  (:require
   [tesla-solar-charger.interfaces.car :as car]
   [clojure.core.async :as async]
   [tesla-solar-charger.interfaces.site :as site]
   [tesla-solar-charger.interfaces.regulator :as regulator]
   [tesla-solar-charger.time-utils :as time-utils]))

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

(defrecord TargetTimeRegulationCreater [settings-chan]

  regulator/RegulationCreater

  (create-regulation [regulation-creater regulator car-state site-data]
    (let [regulation (regulator/->ChargeRateChargeLimitRegulation car-state site-data)
          last-attempted-regulation (regulator/get-last-attempted-regulation regulator)
          last-successful-regulation (regulator/get-last-successful-regulation regulator)
          first-successful-regulation (regulator/get-first-successful-regulation regulator)
          site (regulator/get-site regulator)
          settings (async/<! settings-chan)]
      (when (nil? settings)
        (throw (ex-info "Channel closed" {})))
      (cond
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
        (let [charge-rate-amps (car/get-charge-rate-amps (regulator/get-car-state first-successful-regulation))
              charge-limit-percent (car/get-charge-limit-percent (regulator/get-car-state first-successful-regulation))]
          (-> regulation
              (regulator/set-did-car-stop-charging true)
              (regulator/set-charge-rate-amps charge-rate-amps)
              (regulator/set-charge-limit-percent charge-limit-percent)
              (regulator/add-message (format "Car stopped charging"))))

        (not (site/is-car-here? site car-state))
        (-> regulation
            (regulator/add-message (format "Car is not at %s" (site/get-name site))))

        (not (car/is-charging? car-state))
        (-> regulation
            (regulator/add-message "Car is not charging"))

        (and
         (some? (:target-percent settings))
         (not= (car/get-charge-limit-percent car-state) (:target-percent settings)))
        (-> regulation
            (regulator/set-charge-limit-percent (:target-percent settings))
            (regulator/add-message (format "Set charge limit to %s%%" (:target-percent settings))))

        (car/is-override-active? car-state)
        (-> regulation
            (regulator/set-charge-rate-amps (car/get-max-charge-rate-amps car-state))
            (regulator/add-message "Override active"))

        (and
         (some? (:target-hour settings))
         (some? (:target-minute settings))
         (some? (:target-second settings))
         (not (car/will-reach-target-by? car-state (-> (java.time.LocalDateTime/now
                                                        (.withHour (:target-hour settings))
                                                        (.withMinute (:target-minute settings))
                                                        (.withSecond (:target-second settings))
                                                        (.withNano 0))))))
        (-> regulation
            (regulator/set-charge-rate-amps (car/get-max-charge-rate-amps car-state))
            (regulator/add-message (format "Overriding to reach %s%% by %s"
                                           (car/get-charge-limit-percent car-state)
                                           (time-utils/format-time "HH:mm:ss" (-> (java.time.LocalDateTime/now
                                                                                (.withHour (:target-hour settings))
                                                                                (.withMinute (:target-minute settings))
                                                                                (.withSecond (:target-second settings))
                                                                                (.withNano 0)))))))

        (nil? site-data)
        (-> regulation
            (regulator/add-message "No solar data"))

        (and
         (true? (regulator/used-solar-data? last-successful-regulation))
         (= (site/get-time (last (site/get-points site-data)))
            (site/get-time (last (site/get-points (regulator/get-site-data last-successful-regulation))))))
        (-> regulation
            (regulator/add-message "Already regulated this solar data"))
        (nil? (site/get-excess-power-watts (last (site/get-points site-data))))
        (-> regulation
            (regulator/add-message "No excess power"))

        :else
        (let [excess-power (site/get-excess-power-watts (last (site/get-points site-data)))
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


