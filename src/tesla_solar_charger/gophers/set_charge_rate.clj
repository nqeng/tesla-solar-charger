(ns tesla-solar-charger.gophers.set-charge-rate
  (:require
   [tesla-solar-charger.log :as log]
   [tesla-solar-charger.car-charge-setter.car-charge-setter :refer [set-charge-power]]
   [clojure.core.async :refer [>! go alts!]]))

(defn set-charge-rate
  [charge-setter input-ch kill-ch]
  (let [log-prefix "set-charge-rate"]
    (go
      (log/info log-prefix "Process starting...")
      (loop []
        (let [[val ch] (alts! [kill-ch input-ch])]
          (if (= ch kill-ch)
            (log/info log-prefix "Process dying...")
            (let [power-watts val]
              (if (nil? power-watts)
                (log/error log-prefix "Input channel was closed")
                (do
                  (try
                    (log/info log-prefix (format "Setting charge rate to %.2fW..." power-watts))
                    (set-charge-power charge-setter power-watts)
                    (log/info log-prefix "Successfully set charge rate")
                    (catch clojure.lang.ExceptionInfo e
                      (log/error log-prefix (format "Failed to set charge rate; %s" (ex-message e))))
                    (catch Exception e
                      (log/error log-prefix (format "Failed to set charge rate; %s" (ex-message e)))))
                  (recur)))))))
      (log/info log-prefix "Process died"))))

#_(defn set-override
  [car input-ch kill-ch]
  (let [log-prefix "set-override"]
    (go
      (log/info log-prefix "Process starting...")
      (loop []
        (let [[val ch] (alts! [kill-ch input-ch])]
          (if (= ch kill-ch)
            (log/info log-prefix "Process dying...")
            (let [is-override-active val]
              (if (nil? is-override-active)
                (log/error log-prefix "Input channel was closed")
                (if (true? is-override-active)
                  (do
                    (try
                      (log/info log-prefix "Enabling override...")
                      (car/turn-override-on car)
                      (log/info log-prefix "Successfully enabled override")
                      (catch clojure.lang.ExceptionInfo e
                        (log/error log-prefix (format "Failed to enable override; %s" (ex-message e))))
                      (catch Exception e
                        (log/error log-prefix (format "Failed to enable override; %s" (ex-message e)))))
                    (recur))

                  (do
                    (try
                      (log/info log-prefix "Disabling override...")
                      (car/turn-override-off car)
                      (log/info log-prefix "Successfully disabled override")
                      (catch clojure.lang.ExceptionInfo e
                        (log/error log-prefix (format "Failed to disable override; %s" (ex-message e))))
                      (catch Exception e
                        (log/error log-prefix (format "Failed to disable override; %s" (ex-message e)))))
                    (recur))))))))
      (log/info log-prefix "Process died"))))
