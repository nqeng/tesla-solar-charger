(ns tesla-solar-charger.gophers.process-sms-messages
  (:require
    [cheshire.core :as json]
    [taoensso.timbre :as timbre :refer [infof errorf debugf]]
    [better-cond.core :refer [cond] :rename {cond better-cond}]
    [clojure.core.async :refer [>! close! onto-chan! timeout alts! chan <! go]]
    [clj-http.client :as client]))

(defn get-sms-messages
  [clicksend-username clicksend-api-key]
  (-> (client/get
        "https://rest.clicksend.com/v3/sms/inbound"
        {:basic-auth [clicksend-username clicksend-api-key]})
      :body
      json/parse-string
      (get "data")
      (get "data")))

(defn get-sent-messages
  [clicksend-username clicksend-api-key]
  (let [url "https://rest.clicksend.com/v3/sms/history"
        response (client/get url {:basic-auth [clicksend-username clicksend-api-key]})
        messages (-> response
                     :body
                     json/parse-string
                     (get "data")
                     (get "data"))]
    messages))

(defn get-last-sent-message-date
  [clicksend-username clicksend-api-key]
  (-> (get-sent-messages clicksend-username clicksend-api-key)
      last
      (get "date")
      java.time.Instant/ofEpochSecond))

(defn mark-all-as-read
  [clicksend-username clicksend-api-key]
  (let [url "https://rest.clicksend.com/v3/sms/inbound-read"]
    (client/put url {:basic-auth [clicksend-username clicksend-api-key]})))

(defn mark-as-read
  [clicksend-username clicksend-api-key sms-message]
  (let [message-id (get sms-message "message_id")
        url (str "https://rest.clicksend.com/v3/sms/inbound-read/" message-id)]
    (client/put url {:basic-auth [clicksend-username clicksend-api-key]})))

(defn perform-and-return-error
  [foo]
  (try
    (let [result (foo)]
      {:err nil :val result})
    (catch clojure.lang.ExceptionInfo err
      {:err err :val nil})
    (catch Exception err
      {:err err :val nil})))

(defn time-after-seconds
  [seconds]
  (.plusSeconds (java.time.Instant/now) seconds))

(defn sleep-until
  [datetime]
  (let [millis (.until (java.time.Instant/now) 
                       datetime 
                       java.time.temporal.ChronoUnit/MILLIS)]
    (when (pos? millis)
      (Thread/sleep millis))))


(defn fetch-new-sms-messages
  [clicksend-username clicksend-api-key output-ch kill-ch prefix]
  (close!
    (go
    (infof "[%s] Process started" prefix)
    (loop [next-poll-time (java.time.Instant/now)]

        (better-cond

          :let [sleep-ch (go (sleep-until next-poll-time))]
          :let [[val ch] (alts! [kill-ch sleep-ch])]

          :do (close! sleep-ch)

          (= kill-ch ch) (infof "[%s] Received kill signal" prefix)

          :do (debugf "[%s] Fetching new sms messages..." prefix)
          :let [work #(get-sms-messages clicksend-username clicksend-api-key)]
          :let [result-ch (go (perform-and-return-error work))]
          :let [[val ch] (alts! [kill-ch result-ch])]

          :do (close! result-ch)

          (= kill-ch ch) (infof "[%s] Received kill signal" prefix)

          :let [{err :err messages :val} val]

          (some? err)
          (let [next-poll-time (time-after-seconds 30)]
            (errorf "[%s] Failed to fetch messages; %s" prefix err)
            (debugf "[%s] Sleeping until %s" prefix next-poll-time)
            (recur next-poll-time))

          (empty? messages) 
          (let [next-poll-time (time-after-seconds 30)]
            (debugf "[%s] No new messages" prefix)
            (debugf "[%s] Sleeping until %s" prefix next-poll-time)
            (recur next-poll-time))

          :do (infof "[%s] Fetched %d new messages" prefix (count messages))

          :do (debugf "[%s] Marking messages as read..." prefix)
          :let [work #(mark-all-as-read clicksend-username clicksend-api-key)]
          :let [result-ch (go (perform-and-return-error work))]
          :let [[val ch] (alts! [kill-ch result-ch])]

          :do (close! result-ch)

          (= kill-ch ch) (infof "[%s] Received kill signal" prefix)

          :let [{err :err} val]

          (some? err)
          (let [next-poll-time (time-after-seconds 30)]
            (errorf "[%s] Failed to mark messages as read; %s" prefix err)
            (debugf "[%s] Sleeping until %s" prefix next-poll-time)
            (recur next-poll-time))

          :do (debugf "[%s] Putting value on channel..." prefix)
          :let [put-ch (onto-chan! output-ch messages false)]
          :let [[val ch] (alts! [put-ch kill-ch])]

          :do (close! put-ch)

          (= kill-ch ch) (infof "[%s] Received kill signal" prefix)

          (false? val) (errorf "[%s] Output channel was closed" prefix)

          :do (debugf "[%s] Put value on channel" prefix)

          :let [next-poll-time (time-after-seconds 30)]
          :do (debugf "[%s] Sleeping until %s" prefix next-poll-time)
          (recur next-poll-time)))

    (infof "[%s] Process ended" prefix))))

(defn clamp-min-max
  [num min max]
  (cond
    (> num max) max
    (< num min) min
    :else       num))

(defn kill
  [text settings kill-ch prefix]
  (if (some? (re-find #"^[kK]ill$" text))
    (do
      (infof "[%s] Sending kill signal..." prefix)
      (close! kill-ch)
      true)
    false))

(defn power-buffer
  [text settings kill-ch prefix]
  (if-some [match (re-find #"^[bB]uffer +(-?\d+)$" text)]
    (if-some [buffer-watts (parse-double (second match))]
      (do
        (swap! settings #(assoc % :power-buffer-watts buffer-watts))
        (infof "[%s] Set power buffer to %.2fW" prefix buffer-watts)
        true)
      false)
    false))

(defn target-percent
  [text settings kill-ch prefix]
  (if-some [match (re-find #"^[pP]ercent +(\d\d?\d?)$" text)]
    (if-some [percent (parse-double (second match))]
      (do
        (swap! settings #(assoc % :target-percent percent))
        (infof "[%s] Set target percent to %d%%" prefix percent)
        true)
      false)
    false))

(defn target-time
  [text settings kill-ch prefix]
  (if-some [match (re-find #"^[tT]ime +(\d\d?):(\d\d)$" text)]
    (if-some [hour (parse-long (second match))]
      (if-some [minute (parse-long (last match))]
        (cond
          (> hour 24) false
          (< hour 0) false
          (> minute 60) false
          (< minute 0) false

          :else
          (do
            (swap! settings #(assoc % :target-hour hour))
            (swap! settings #(assoc % :target-minute minute))
            (infof "[%s] Set target time to %02d:%02d" prefix hour minute)
            true))
        false)
      false)
    false))

(defn process-message
  [message settings kill-ch prefix]
  (better-cond

    :let [body (get message "body")]

    (nil? body) false

    :let [trimmed (clojure.string/trim body)]

    (true? (kill trimmed settings kill-ch prefix))
    true

    (true? (power-buffer trimmed settings kill-ch prefix))
    true

    (true? (target-percent trimmed settings kill-ch prefix))
    true

    (true? (target-time trimmed settings kill-ch prefix))
    true
    
    :do (infof "[%s] Invalid message" prefix)

    :else
    false))

(defn process-sms-messages
  [input-ch settings kill-ch prefix]
  (close!
    (go
      (infof "[%s] Process started" prefix)
      (loop []
        (better-cond
          :do (debugf "[%s] Waiting for value..." prefix)

          :let [[val ch] (alts! [kill-ch input-ch])]

          :do (debugf "[%s] Took value off channel" prefix)

          (= kill-ch ch) (infof "[%s] Received kill signal" prefix)

          (nil? val) (errorf "[%s] Input channel was closed" prefix)

          :let [message val]

          :let [result (process-message message settings kill-ch prefix)]

          (false? result)
          (recur)

          (recur)))

      (infof "[%s] Process ended" prefix))))

(defn process-new-sms-messages
  [clicksend-username clicksend-api-key settings kill-ch prefix]
  (let [new-sms-ch (chan)]
    (fetch-new-sms-messages clicksend-username clicksend-api-key new-sms-ch kill-ch "Fetch SMS")
    (process-sms-messages new-sms-ch settings kill-ch "Process SMS")
    )
  )

