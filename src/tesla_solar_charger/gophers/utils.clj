(ns tesla-solar-charger.gophers.utils
  (:require
   [tesla-solar-charger.utils :as utils]
   [tesla-solar-charger.log :as log]
   [clojure.core.async :as async]))

(defn timer
  [duration-ms]
  (let [output (async/chan)]
    (async/go-loop []
      (Thread/sleep duration-ms)
      (async/>! output 0)
      (recur))
    output))

(defn print-values
  ([format-str input-ch]
   (async/go-loop []
     (when-some [val (async/<! input-ch)]
       (log/info (format format-str
                         (if (satisfies? utils/Printable val)
                           (utils/string-this val)
                           (str val))))
       (recur))))
  ([input-ch]
   (print-values "%s" input-ch)))

(defn sliding-buffer
  [input-ch size]
  (when (nil? input-ch)
    (throw (java.lang.IllegalArgumentException. "Channel was nil")))
  (let [output-ch (async/chan (async/sliding-buffer size))]
    (async/go
      (loop []
        (let [val (async/<! input-ch)]
          (when (some? val)
            (async/>! output-ch val)
            (recur))))
      (async/close! output-ch))
    output-ch))

(comment
  (let [input-ch (async/chan)
      output-ch (sliding-buffer input-ch 1)]

  (async/>!! input-ch 0)
  (async/>!! input-ch 1)
  (async/>!! input-ch 2)
  (async/>!! input-ch 3)

  (Thread/sleep 1)

  (assert (= 3 (async/<!! output-ch)))

  (async/>!! input-ch 1)

  (Thread/sleep 1)

  (assert (= 1 (async/<!! output-ch)))

  (async/close! input-ch)))

(defn split-ch
  [input-ch & output-chs]
  (async/go-loop []
                 (let [val (async/<! input-ch)]
                   (when (some? val)
                     (doseq [ch output-chs] (async/>! ch val))
                     (recur)))))

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

(defn keep-last-value
  [input-ch]
  (when (nil? input-ch)
    (throw (java.lang.IllegalArgumentException. "Channel was nil")))
  (let [output-ch (async/chan (async/sliding-buffer 1))]
    (async/go
      (let [initial-val (async/<! input-ch)]
        (when (some? initial-val)
          (loop [last-val initial-val]
            (let [[result chan] (async/alts! [[output-ch last-val] input-ch])]
              (if (= input-ch chan)
                (when-some [new-val result]
                  (recur new-val))
                (when-let [success result]
                  (recur last-val)))))))
      (async/close! output-ch))
    output-ch))

(comment
  (let [input-ch (async/chan)
      output-ch (keep-last-value input-ch)]

  (async/>!! input-ch 0)

  (Thread/sleep 1)

  (assert (= 0 (async/<!! output-ch)))
  (assert (= 0 (async/<!! output-ch)))
  (assert (= 0 (async/<!! output-ch)))
  (assert (= 0 (async/<!! output-ch)))

  (async/>!! input-ch 1)

  (Thread/sleep 1)

  (assert (= 1 (async/<!! output-ch)))
  (assert (= 1 (async/<!! output-ch)))
  (assert (= 1 (async/<!! output-ch)))
  (assert (= 1 (async/<!! output-ch)))

  (async/close! input-ch)))
