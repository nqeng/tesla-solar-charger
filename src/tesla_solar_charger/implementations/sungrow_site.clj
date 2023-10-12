(ns tesla-solar-charger.implementations.sungrow-site
  (:require
   [tesla-solar-charger.interfaces.car :as car]
   [better-cond.core :as b]
   [tesla-solar-charger.time-utils :refer :all]
   [tesla-solar-charger.sungrow :as sungrow]
   [tesla-solar-charger.interfaces.site :as site]
   [tesla-solar-charger.time-utils :as time-utils]))

(defn euclidean-distance
  [x1 y1 x2 y2]
  (Math/sqrt (+ (Math/pow (- x2 x1) 2) (Math/pow (- y2 y1) 2))))

(defrecord SungrowSiteData []

  site/SiteData

  (add-point [data point] (update data :points #(vec (conj % point))))
  (get-points [data] (:points data)))

(defrecord SungrowSiteDataPoint [time excess-power-watts]

  site/SiteDataPoint

  (get-time [point] (get point :time))
  (get-excess-power-watts [point] (get point :excess-power-watts)))

(defrecord SungrowSite [name latitude longitude username password values]

  site/Site

  (get-name [site] name)
  (is-car-here? [site car-state]
    (< (euclidean-distance
        (car/get-latitude car-state)
        (car/get-longitude car-state)
        latitude
        longitude) 0.0005))
  (has-new-data-since-request? [site request]
    (or
     (nil? request)
     (nil? (:end-time request))
     (nil? (:latest-data-available-time site))
     (.isAfter (:latest-data-available-time site) (:end-time request))))

  (power-watts-to-current-amps [site power-watts] (/ power-watts 687.5))

  (get-data [site request]
    (try
      (b/cond
        (nil? (:start-time request))
        nil

        (nil? (:end-time request))
        nil

        :let [sungrow-token (if (some? (:sungrow-token site))
                              (:sungrow-token site)
                              (sungrow/login username password))]

        :let [data (try
                     (apply sungrow/get-data
                            sungrow-token
                            (:start-time request)
                            (:end-time request)
                            5
                            (map last values))
                     (catch clojure.lang.ExceptionInfo e
                       (case (:type (ex-data e))
                         :err-sungrow-auth-failed
                         (try
                           (apply sungrow/get-data
                                  (sungrow/login username password)
                                  (:start-time request)
                                  (:end-time request)
                                  5
                                  (map last values))
                           (catch clojure.lang.ExceptionInfo e
                             (throw e))
                           (catch Exception e
                             (throw e)))))
                     (catch Exception e
                       (throw e)))]

        :let [site (assoc site :next-data-available-time (.plusSeconds (sungrow/get-next-data-publish-time (time-now)) 60))]
        :let [site (assoc site :latest-data-available-time (sungrow/get-latest-data-publish-time (time-now)))]
        :let [excess-power-keys (:excess-power-watts values)
              excess-power (-> data
                               (get-in excess-power-keys)
                               first
                               (get 1 "--"))
              excess-power (try (- (Float/parseFloat excess-power)) (catch Exception e nil))
              time (-> data
                       (get-in excess-power-keys)
                       first
                       (get 0 (time-utils/format-time "yyyyMMddHHmmss" (:latest-data-available-time site)))
                       (time-utils/parse-time "yyyyMMddHHmmss"))]

        :let [data (-> (->SungrowSiteData)
                       (site/add-point (->SungrowSiteDataPoint time excess-power)))]

        [site data])
      (catch clojure.lang.ExceptionInfo e
        (throw e))
      (catch Exception e
        (throw e)))))

(comment
  (let [site (->SungrowSite
              "North Queensland Engineering"
              -19.291017028657112
              146.79517661638516
              "reuben@nqeng.com.au"
              "sungrownqe123"
              {:excess-power-watts ["1152381_7_2_3" "p8018"]})]
    (site/get-data site {:start-time (time-now) :end-time (time-now)})))
