(ns tesla-solar-charger.implementations.site-data.sungrow-live-data-source
  (:require
   [etaoin.api :as e]
   [etaoin.keys :as k]
   [clojure.string :as s]
   [tesla-solar-charger.interfaces.site :as Isite]
   [tesla-solar-charger.interfaces.site-data :as Isite-data]
   [tesla-solar-charger.utils :as utils]))

(defn parse-value-in-watts
  [value-string]
  (let [[match value units] (re-find #"\s*(-?\d+.?\d*)\s*(\w+)" value-string)
        value (Float/parseFloat value)
        value (if (= "kW" units) (* 1000 value) value)]
    value))

(defn get-excess-power
  [browser-type browser-options username password plant-name]
  (e/with-driver browser-type browser-options driver
    (e/go driver "https://au.isolarcloud.com")
    (e/wait-visible driver :userAcct)
    (e/fill driver {:tag :input :name :userAcct} username)
    (e/fill driver {:tag :input :name :userPswd} password)
    (when (e/exists? driver :privacyLabel)
      (e/click driver :privacyLabel))
    (e/fill driver {:tag :input :name :userPswd} k/enter)
    (try
      (e/wait-visible driver {:class :privacy-agree})
      (e/click driver {:class :privacy-agree})
      (catch clojure.lang.ExceptionInfo e
        (case (:type (ex-data e))
          :etaoin/timeout
          nil

          (throw e))))

    (e/wait 1)
    (e/go driver "https://portalau.isolarcloud.com/#/senior/secondLevel")
    (e/wait-visible driver {:fn/has-text "Meter Active Power"})
    (e/click driver {:fn/has-text plant-name})
    (e/wait-visible driver [{:fn/has-text "Meter Active Power"}])

    (let [excess-power-element-id (e/query driver [{:fn/has-text "Meter Active Power"} "../.." {:fn/has-text "W"}])
          element-text (e/get-element-text-el driver excess-power-element-id)
          element-text (clojure.string/trim element-text)
          value (if (= "--" element-text) nil (parse-value-in-watts element-text))]
      value)))

(defrecord SungrowLiveDataSource []

  Isite-data/SiteDataSource

  (get-latest-data-point [data-source]
    (let [browser-type (:browser-type data-source)
          browser-options (:browser-options data-source)
          username (:username data-source)
          password (:password data-source)
          plant-name (:plant-name data-source)
          excess-power-watts (get-excess-power
                              browser-type
                              browser-options
                              username
                              password
                              plant-name)
          data (Isite-data/make-data-point (utils/local-now) excess-power-watts)
          next-data-time (utils/time-after-seconds 30)]
      [data next-data-time])))

(defn new-SungrowLiveDataSource
  [browser-type browser-options username password plant-name]
  (let [the-map {:browser-type browser-type
                 :browser-options browser-options
                 :username username
                 :password password
                 :plant-name plant-name}
        defaults {:next-data-available-time (utils/local-now)}]
    (map->SungrowLiveDataSource (merge the-map defaults))))

