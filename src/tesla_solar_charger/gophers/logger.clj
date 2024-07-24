(ns tesla-solar-charger.gophers.logger
  (:require
   [clojure.core.async :as async]
   [clojure.string :as s]
   [clojure.java.io :refer [make-parents]]
   [tesla-solar-charger.utils :as utils]))

(defn make-log-file-path
  [time]
  (let [year-folder (utils/format-local-time "yy" time)
        month-folder (utils/format-local-time "MM" time)
        log-file (utils/format-local-time "yy-MM-dd" time)
        log-file-path (format "./logs/%s/%s/%s.log"
                              year-folder
                              month-folder
                              log-file)]
    log-file-path))

(def log-levels {:verbose #{:error :verbose :info}
                 :info #{:error :info}
                 :error #{:error}})

(defn log
  [log-level message-level prefix & args]
  (let [permitted-levels (get log-levels log-level)]
    (when (contains? permitted-levels message-level)
      (let [time (utils/local-now)
            log-timestamp (utils/format-local-time time)
            prefix (if (some? prefix) prefix "Misc")
            log-message (format "[%s] [%s] %s" log-timestamp prefix (s/join "\n" args))
            log-file-path (make-log-file-path time)]

        (println log-message)
        (make-parents log-file-path)
        (spit log-file-path (str log-message "\n") :append true)))))

(defn log-loop
  [log-level log-chan error-chan]
  (async/go
    (try
      (loop []
        (let [message (async/<! log-chan)]
          (when (nil? message)
            (throw (ex-info "Channel closed!" {})))
          (if (string? message)
            (log log-level :info nil message)
            (log log-level (:level message) (:prefix message) (:message message))))
        (recur))
      (catch clojure.lang.ExceptionInfo e
        (async/>! error-chan e))
      (catch Exception e
        (async/>! error-chan e)))))
