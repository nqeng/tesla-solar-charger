(ns tesla-solar-charger.gophers.provide-settings
  (:require
   [clojure.core.async :refer [go >! <! ]]
   [cheshire.core :as json]))

(def default-settings
  {"target_time_hour" 16
   "target_time_minute" 30
   "target_time_second" 0
   "target_percent" 80})

(defn provide-settings
  [log-prefix settings-filename get-chan set-chan error-chan log-chan]
  (async/go
    (try
      (loop [settings (if (or
                           (clojure.string/blank? settings-filename)
                           (not (clojure.string/ends-with? settings-filename ".json")))
                        default-settings
                        (try
                          (json/parse-string (slurp settings-filename))
                          (catch Exception e
                            default-settings)))]
        (async/>! log-chan {:level :verbose 
                            :prefix log-prefix 
                            :message "..."})
        (let [[value chan] (async/alts! [[get-chan settings] set-chan])]
          (if (= get-chan chan)
            (let [success value]
              (when (false? success)
                (throw (ex-info "Channel closed!" {})))
              (async/>! log-chan {:level :info :prefix log-prefix :message "Provided current settings"})
              (recur settings))
            (let [set-request value]
              (when (nil? set-request)
                (throw (ex-info "Channel closed!" {})))
              (let [new-settings (set-request settings)]
                (async/>! log-chan {:level :info :prefix log-prefix :message "Received change request"})
                (async/>! log-chan {:level :verbose :prefix log-prefix :message new-settings})
                (when (and
                       (not (clojure.string/blank? settings-filename))
                       (clojure.string/ends-with? settings-filename ".json"))
                  (spit settings-filename (json/generate-string new-settings {:pretty true})))
                (recur new-settings))))))
      (catch clojure.lang.ExceptionInfo e
        (async/>! error-chan e))
      (catch Exception e
        (async/>! error-chan e)))))
