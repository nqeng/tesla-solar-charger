(ns tesla-solar-charger.gophers.get-site-data
  (:require
   [tesla-solar-charger.interfaces.site :as Isite]
   [tesla-solar-charger.log :as log]
   [tesla-solar-charger.interfaces.site-data :as Isite-data]
   [better-cond.core :refer [cond] :rename {cond better-cond}]
   [clojure.core.async :as async]
   [tesla-solar-charger.utils :as utils]))

(def state {:site nil
            :last-site-data nil})

(defn get-site-data
  [site request]
  (try
    (let [[site data] (Isite/get-data site request)]
      [site data nil])
    (catch clojure.lang.ExceptionInfo e
      [site nil e])))

(defn whichever-site-data-is-newer
  [site-data1 site-data2]
  (if (Isite-data/is-newer? site-data1 site-data2)
    site-data1
    site-data2))

(defn run
  [state]
  (let [site (:site state)
        request {:start-time (java.time.LocalDateTime/now)
                 :end-time (java.time.LocalDateTime/now)}
        last-site-data (:last-site-data state)]
    (try
      (let [[site site-data] (Isite/get-data site request)
            state (assoc state :site site)
            next-site-data (whichever-site-data-is-newer site-data last-site-data)
            value-to-output (if (Isite-data/is-newer? site-data last-site-data)
                              site-data
                              nil)
            state (assoc state :last-site-data next-site-data)
            next-run-at (Isite/when-next-data-ready? site)]
        (log/verbose (format "Last site data: %s"
                             (if (some? last-site-data)
                               (utils/format-time (Isite-data/get-latest-time last-site-data))
                               "none")))
        (log/verbose (format "New site data:  %s"
                             (utils/format-time (Isite-data/get-latest-time site-data))))
        [state value-to-output true next-run-at])
      (catch Exception e
        (log/error (ex-message e))
        [state nil true nil])
      (catch clojure.lang.ExceptionInfo e
        (log/error (ex-message e))
        [state nil true nil]))))

(defn get-new-site-data
  [log-prefix site error-ch kill-ch]
  (let [output-ch (async/chan)]
    (async/go
      (try
        (loop [state (-> state
                         (assoc :site site))]
          (let [[_ ch] (async/alts! [kill-ch (async/timeout 0)])]
            (when-not (= ch kill-ch)
              (let [[new-state value should-continue next-run-at] (run state)]
                (when (some? value)
                  (if-let [success (async/>! output-ch value)]
                    (log/verbose "value -> channel")
                    (throw utils/output-channel-closed)))
                (when should-continue
                  (when (some? next-run-at)
                    (log/info (format "Sleeping until %s" (utils/format-time next-run-at)))
                    (utils/sleep-until next-run-at))
                  (recur new-state))))))
        (catch clojure.lang.ExceptionInfo e
          (async/>! error-ch e))
        (catch Exception e
          (async/>! error-ch e)))
      (log/verbose "Closing channel...")
      (async/close! output-ch)
      (log/verbose "Done."))
    output-ch))

(comment
  (do
    (require '[tesla-solar-charger.implementations.site.sungrow-site :refer [new-SungrowSite]])
    (require '[tesla-solar-charger.implementations.site-data.dummy-data-source :refer [new-DummyDataSource]])
    (require '[tesla-solar-charger.implementations.site-data.sungrow-live-data-source :refer [new-SungrowLiveDataSource]])
    (let [error-ch (async/chan (async/sliding-buffer 1))
          kill-ch (async/chan (async/sliding-buffer 1))
          data-source (new-SungrowLiveDataSource
                       :firefox
                       {:headless true}
                       "reuben@nqeng.com.au"
                       "sungrownqe123"
                       "North Queensland Engineering")
          ;data-source (new-DummyDataSource)
          site (-> (new-SungrowSite "" "" 0 0)
                   (Isite/with-data-source data-source))
          output-ch (get-new-site-data "Site Data" site error-ch kill-ch)]

      (async/go-loop []
        (when-some [err (async/<! error-ch)]
          (println "Took" err)
          (recur)))

      (async/go-loop []
        (when-some [val (async/<! output-ch)]
          (println "Took" (utils/string-this val))
          (recur)))

      (println "Fin.")

      (Thread/sleep 60000)
      (async/>!! kill-ch :DIE))))


