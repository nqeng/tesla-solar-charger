(ns tesla-solar-charger.gophers.provide-current-channel-value
  (:require [clojure.core.async :as async]))

(defn provide-current-channel-value
  [input-chan output-chan error-chan log-chan]
  (async/go
    (try
      (loop [current-val nil]
        (if (nil? current-val)
          (do
            (let [initial-val (async/<! input-chan)]
              (when (nil? initial-val)
                (throw (ex-info "Channel closed!" {})))
              (recur initial-val)))
          (do
            (let [[val chan] (async/alts! [[output-chan current-val] input-chan])]
              (if (= output-chan chan)
                (let [success val]
                  (when (false? success)
                    (throw (ex-info "Channel closed!" {})))
                  (recur current-val))
                (let [new-val val]
                  (when (nil? new-val)
                    (throw (ex-info "Channel closed!" {})))
                  (recur new-val)))))))
      (catch clojure.lang.ExceptionInfo e
        (async/>! error-chan e))
      (catch Exception e
        (async/>! error-chan e)))))

(let [input-chan (async/chan)
      output-chan (async/chan)
      error-chan (async/chan (async/dropping-buffer 1))
      log-chan (async/chan (async/dropping-buffer 1))
      ]

  (provide-current-channel-value input-chan output-chan error-chan log-chan)

  (async/>!! input-chan 0)

  (assert (= 0 (async/<!! output-chan)))
  (assert (= 0 (async/<!! output-chan)))
  (assert (= 0 (async/<!! output-chan)))
  (assert (= 0 (async/<!! output-chan)))

  (async/close! input-chan)
  (async/close! output-chan)
  (async/close! error-chan)
  (async/close! log-chan)
  )
