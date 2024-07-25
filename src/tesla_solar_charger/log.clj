(ns tesla-solar-charger.log
  (:require
   [clojure.java.io :refer [make-parents]]
   [clojure.core.async :as async]
   [clj-http.client :as client]
   [clojure.string :as s]
   [tesla-solar-charger.utils :as utils]))

(def log-message (atom ""))
(def log-level (atom :verbose))

(defn make-log-file-path
  [time]
  (let [year-folder (utils/format-time "yy" time)
        month-folder (utils/format-time "MM" time)
        log-file (utils/format-time "yy-MM-dd" time)
        log-file-path (format "./logs/%s/%s/%s.log"
                              year-folder
                              month-folder
                              log-file)]
    log-file-path))

(def default-log-level :verbose)

(def log-levels {:verbose #{:error :verbose :info}
                 :info #{:error :info}
                 :error #{:error}})

(defn set-log-level
  [new-log-level]
  (reset! log-level new-log-level))

(defn log
  ([message-level prefix message]
   (let [permitted-levels (get log-levels (deref log-level))]
     (when (contains? permitted-levels message-level)
       (let [time (utils/local-now)
             log-timestamp (utils/format-time time)
             prefix (if (some? prefix) prefix "Misc")
             log-message (format "[%s] [%s] %s" log-timestamp prefix message)
             log-file-path (make-log-file-path time)]

         (println log-message)
         (make-parents log-file-path)
         (spit log-file-path (str log-message "\n") :append true)))))
  ([message-level message]
   (log message-level "Misc" message)))

(defn send-to-ntfy
  [channel-name message]
  (try
    (client/post (format "https://ntfy.sh/%s" channel-name) message)
    (catch Exception e nil)
    (catch clojure.lang.ExceptionInfo e nil)))

(def info (partial log :info))
(def verbose (partial log :verbose))
(def error (partial log :error))
(defn notify
  [message]
  (send-to-ntfy "" message))


