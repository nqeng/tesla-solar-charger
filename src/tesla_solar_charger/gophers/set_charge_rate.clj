(ns tesla-solar-charger.gophers.set-charge-rate
  (:require
   [taoensso.timbre :as timbre]
   [tesla-solar-charger.car-charge-setter.car-charge-setter :refer [set-charge-power]]
   [clojure.core.async :refer [>! go alts!]]))

(defn perform-and-return-error
  [foo]
  (try
    (let [result (foo)]
      {:err nil :val result})
    (catch clojure.lang.ExceptionInfo err
      {:err err :val nil})
    (catch Exception err
      {:err err :val nil})))

(defn set-charge-rate
  [charge-setter input-ch kill-ch log-prefix]
  (letfn [(info [msg] (timbre/info (format "[%s]" log-prefix) msg))
          (error [msg] (timbre/error (format "[%s]" log-prefix) msg))
          (debug [msg] (timbre/debug (format "[%s]" log-prefix) msg))]
    (go
      (info "Process starting...")
      (loop []
        (debug "Taking value off channel...")
        (let [[val ch] (alts! [kill-ch input-ch])]
          (if (= ch kill-ch)
            (info "Process dying...")
            (if (nil? val)
              (error "Input channel was closed")
              (let [power-watts val
                    _ (info (format "Setting charge rate to %.2fW..." (float power-watts)))
                    foo (fn [] (set-charge-power charge-setter power-watts))
                    result-ch (go (perform-and-return-error foo))
                    [val ch] (alts! [kill-ch result-ch])]
                (if (= ch kill-ch)
                  (info "Process dying...")
                  (let [{err :err} val]
                    (if (some? err)
                      (do
                        (error (format "Failed to set charge rate; %s" (ex-message err)))
                        (recur))
                      (do
                        (info "Successfully set charge rate")
                        (recur))))))))))
      (info "Process died"))))

#_(defn set-override
  [car input-ch kill-ch]
  (let [log-prefix "set-override"]
    (go
      (info log-prefix "Process starting...")
      (loop []
        (let [[val ch] (alts! [kill-ch input-ch])]
          (if (= ch kill-ch)
            (info log-prefix "Process dying...")
            (let [is-override-active val]
              (if (nil? is-override-active)
                (error log-prefix "Input channel was closed")
                (if (true? is-override-active)
                  (do
                    (try
                      (info log-prefix "Enabling override...")
                      (car/turn-override-on car)
                      (info log-prefix "Successfully enabled override")
                      (catch clojure.lang.ExceptionInfo e
                        (error log-prefix (format "Failed to enable override; %s" (ex-message e))))
                      (catch Exception e
                        (error log-prefix (format "Failed to enable override; %s" (ex-message e)))))
                    (recur))

                  (do
                    (try
                      (info log-prefix "Disabling override...")
                      (car/turn-override-off car)
                      (info log-prefix "Successfully disabled override")
                      (catch clojure.lang.ExceptionInfo e
                        (error log-prefix (format "Failed to disable override; %s" (ex-message e))))
                      (catch Exception e
                        (error log-prefix (format "Failed to disable override; %s" (ex-message e)))))
                    (recur))))))))
      (info log-prefix "Process died"))))
