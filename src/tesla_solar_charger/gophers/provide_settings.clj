(ns tesla-solar-charger.gophers.provide-settings
  (:require
   [clojure.core.async :as async]))

(def default-settings
  {:target-time-hour 16
   :target-time-minute 30
   :target-time-second 0
   :target-percent 80})

(defn provide-settings
  [log-prefix settings-filename get-chan set-chan error-chan log-chan]
  (async/go
    (try
      (loop [settings default-settings]
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
                (recur new-settings))))))
      (catch clojure.lang.ExceptionInfo e
        (async/>! error-chan e))
      (catch Exception e
        (async/>! error-chan e)))))
