(ns tesla-solar-charger.gophers.split-channel
  (:require
   [clojure.core.async :as async]))

(defn split-channel
  [input-chan output-chans error-chan log-chan]
  (async/go
    (try
      (loop []
        (let [val (async/<! input-chan)]
          (when (nil? val)
            (throw (ex-info "Channel closed" {})))
          (doseq [chan output-chans]
            (when (false? (async/>! chan val))
              (throw (ex-info "Channel closed" {})))))
        (recur))
      (catch Exception e
        (async/>! error-chan e))
      (catch clojure.lang.ExceptionInfo e
        (async/>! error-chan e)))))

(let [input-chan (async/chan)
      chan1 (async/chan)
      chan2 (async/chan)
      chan3 (async/chan)
      log-chan (async/chan (async/dropping-buffer 1))
      error-chan (async/chan (async/dropping-buffer 1))
      ]
  (split-channel input-chan [chan1 chan2 chan3] error-chan log-chan)
  (async/>!! input-chan 0)
  (assert (= 0 (async/<!! chan1)))
  (assert (= 0 (async/<!! chan2)))
  (assert (= 0 (async/<!! chan3)))
  #_(Thread/sleep 10000)
  (async/close! input-chan)
  (async/close! chan1)
  (async/close! chan2)
  (async/close! chan3)
  (println "Done")
  )
