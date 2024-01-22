(ns tesla-solar-charger.gophers.split-channel
  (:require
   [clojure.core.async :as async]))

(defn split-channel
  [input-chan num-channels]
  (let [output-chs (repeat num-channels (async/chan))]
    (async/go
      (loop []
        (let [val (async/<! input-chan)]
          (when (some? val)
            (doseq [ch output-chs] (async/>! ch val))
            (recur))))
      (doseq [ch output-chs] (async/close! ch)))
    output-chs))

(comment
  (let [input-chan (async/chan)
        [ch1 ch2 ch3] (split-channel input-chan 3)]
    (async/>!! input-chan 0)
    (assert (= 0 (async/<!! ch1)))
    (assert (= 0 (async/<!! ch2)))
    (assert (= 0 (async/<!! ch3)))
    #_(Thread/sleep 10000)
    (async/close! input-chan)
    (println "Done")))
