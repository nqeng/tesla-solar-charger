(ns tesla-solar-charger.implementations.sms-processors
  (:require
   [clojure.core.async :as async]
   [better-cond.core :refer [cond] :rename {cond better-cond}]
   [tesla-solar-charger.interfaces.site :as site]
   [tesla-solar-charger.interfaces.car :as car]
   [tesla-solar-charger.utils :as utils]
   [tesla-solar-charger.interfaces.sms :as sms]))

(defrecord SetPowerBuffer [set-settings-chan get-settings-chan car car-state-chan solar-sites]

  sms/SMSProcessor

  (process-sms
    [processor sms]
    (try
      (better-cond

       :let [body (get sms "body")
             match (re-find #"^\s*[bB]uffer\s+(\d+)\s*$" body)
             power-buffer-watts (Float/parseFloat (get match 1))
             power-buffer-watts (utils/clamp-min-max power-buffer-watts 0 99999)]

       :let [car-state (async/<!! car-state-chan)]

       (nil? car-state)
       (throw (ex-info "Channel closed" {}))

       :let [current-site (first (filter #(site/is-car-here? % car-state) solar-sites))]

       (nil? current-site)
       false

       :let [settings-key (str (site/get-id current-site) (car/get-vin car))
             settings-action (fn [settings]
                               (-> settings
                                   (assoc-in [settings-key "power_buffer_watts"] power-buffer-watts)))]
       (and (some? settings-action)
            (false? (async/>!! set-settings-chan settings-action)))
       (throw (ex-info "Channel closed" {}))

       :else
       true)
      (catch NumberFormatException e
        false)
      (catch NullPointerException e
        false))))

(defrecord SetTargetPercent [set-settings-chan get-settings-chan car car-state-chan solar-sites]

  sms/SMSProcessor

  (process-sms
    [processor sms]
    (try
      (better-cond
       :let [body (get sms "body")
             match (re-find #"^\s*[pP]ercent\s+(\d\d?\d?)\s*$" body)
             target-percent (Integer/parseInt (get match 1))
             target-percent (utils/clamp-min-max target-percent 0 100)]

       :let [car1-state (async/<!! car-state-chan)]

       (nil? car1-state)
       (throw (ex-info "Channel closed" {}))

       :let [current-site (first (filter #(site/is-car-here? % car1-state) solar-sites))]

       (nil? current-site)
       false

       :let [settings-key (str (site/get-id current-site) (car/get-vin car))
             settings-action (fn [settings]
                               (-> settings
                                   (assoc-in [settings-key "target_percent"] target-percent)))]
       (and (some? settings-action)
            (false? (async/>!! set-settings-chan settings-action)))
       (throw (ex-info "Channel closed" {}))

       :else
       true)
      (catch NumberFormatException e
        false)
      (catch java.time.DateTimeException e
        false)
      (catch NullPointerException e
        false))))

(defrecord SetTargetTime [set-settings-chan get-settings-chan car car-state-chan solar-sites]

  sms/SMSProcessor

  (process-sms
    [processor sms]
    (try
      (better-cond
       :let [body (get sms "body")
             match (re-find #"^\s*[tT]ime\s+(\d\d?):(\d\d?)\s*$" body)
             target-hour (Integer/parseInt (get match 1))
             target-minute (Integer/parseInt (get match 2))]

       :do (-> (java.time.LocalDateTime/now)
               (.withHour target-hour)
               (.withMinute target-minute)
               (.withSecond 0)
               (.withNano 0))

       :let [car-state (async/<!! car-state-chan)]

       (nil? car-state)
       (throw (ex-info "Channel closed" {}))

       :let [current-site (first (filter #(site/is-car-here? % car-state) solar-sites))]

       (nil? current-site)
       false

       :let [settings-key (str (site/get-id current-site) (car/get-vin car))
             settings-action (fn [settings]
                               (-> settings
                                   (assoc-in [settings-key "target_time_hour"] target-hour)
                                   (assoc-in [settings-key "target_time_minute"] target-minute)))]
       (and (some? settings-action)
            (false? (async/>!! set-settings-chan settings-action)))
       (throw (ex-info "Channel closed" {}))

       :else
       true)
      (catch NumberFormatException e
        false)
      (catch java.time.DateTimeException e
        false)
      (catch NullPointerException e
        false))))

(defrecord SetMaxClimb [set-settings-chan get-settings-chan car car-state-chan solar-sites]

  sms/SMSProcessor

  (process-sms
    [processor sms]
    (try
      (better-cond
       :let [car-state (async/<!! car-state-chan)]

       (nil? car-state)
       (throw (ex-info "Channel closed" {}))

       :let [body (get sms "body")
             match (re-find #"^\s*[Mm]ax\s+[Cc]limb\s+(\d\d?)\s*$" body)
             max-climb-amps (Integer/parseInt (get match 1))
             max-climb-amps (utils/limit max-climb-amps 0 (car/get-max-charge-rate-amps car-state))]

       :let [current-site (first (filter #(site/is-car-here? % car-state) solar-sites))]

       (nil? current-site)
       false

       :let [settings-key (str (site/get-id current-site) (car/get-vin car))
             settings-action (fn [settings]
                               (-> settings
                                   (assoc-in [settings-key "max_climb_amps"] max-climb-amps)))]
       (and (some? settings-action)
            (false? (async/>!! set-settings-chan settings-action)))
       (throw (ex-info "Channel closed" {}))

       :else
       true)
      (catch NumberFormatException e
        false)
      (catch java.time.DateTimeException e
        false)
      (catch NullPointerException e
        false))))

(defrecord SetMaxDrop [set-settings-chan get-settings-chan car car-state-chan solar-sites]

  sms/SMSProcessor

  (process-sms
    [processor sms]
    (try
      (better-cond
       :let [car-state (async/<!! car-state-chan)]

       (nil? car-state)
       (throw (ex-info "Channel closed" {}))

       :let [body (get sms "body")
             match (re-find #"^\s*[Mm]ax\s+[Dd]rop\s+(\d\d?)\s*$" body)
             max-climb-amps (Integer/parseInt (get match 1))
             max-climb-amps (utils/limit max-climb-amps 0 (car/get-max-charge-rate-amps car-state))]

       :let [current-site (first (filter #(site/is-car-here? % car-state) solar-sites))]

       (nil? current-site)
       false

       :let [settings-key (str (site/get-id current-site) (car/get-vin car))
             settings-action (fn [settings]
                               (-> settings
                                   (assoc-in [settings-key "max_drop_amps"] max-climb-amps)))]
       (and (some? settings-action)
            (false? (async/>!! set-settings-chan settings-action)))
       (throw (ex-info "Channel closed" {}))

       :else
       true)
      (catch NumberFormatException e
        false)
      (catch java.time.DateTimeException e
        false)
      (catch NullPointerException e
        false))))
