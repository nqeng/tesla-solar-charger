(ns tesla-solar-charger.gophers.logger
  (:require
   [better-cond.core :as b]
   [clojure.core.async :as async]
   [clojure.string :as s]
   [clojure.java.io :refer [make-parents]]
   [tesla-solar-charger.time-utils :refer [time-now format-time]]))

(defn make-log-file-path
  [time]
  (let [year-folder (format-time "yy" time)
        month-folder (format-time "MM" time)
        log-file (format-time "yy-MM-dd" time)
        log-file-path (format "./logs/%s/%s/%s.log"
                              year-folder
                              month-folder
                              log-file)]
    log-file-path))

(def log-levels {:verbose #{:error :verbose :info}
                 :info #{:error :info}
                 :error #{:error}})

(def log-level :verbose)

(defn log
  [level & args]
  (let [levels (get log-levels log-level)]
    (when (contains? levels level)
      (let [time (time-now)
            log-timestamp (format-time "yyyy-MM-dd HH:mm:ss" time)
            log-message (format "[%s] %s" log-timestamp (s/join "\n" args))
            log-file-path (make-log-file-path time)]

        (println log-message)
        (make-parents log-file-path)
        (spit log-file-path (str log-message "\n") :append true)))))

(def log-blacklist
  ["![Solar]" "![Car]" "![Logger]"])

(defn is-blacklisted?
  [message]
  (not (not-any? (partial s/starts-with? message) log-blacklist)))

(defn log-loop
  [log-chan error-chan]
  (try
    (loop []
      (b/cond
        :let [message (async/<!! log-chan)]

        (nil? message)
        (throw (ex-info "Channel closed!" {}))

        (is-blacklisted? message)
        nil

        (string? message)
        (log :info message)

        (map? message)
        (log (:level message) (:message message)))

      (recur))
    (catch clojure.lang.ExceptionInfo e
      (async/>!! error-chan e))
    (catch Exception e
      (async/>!! error-chan e))))
