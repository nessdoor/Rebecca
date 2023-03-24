(ns rebecca.core
  (:require [clojure.core.async :as a]
            [clojure.string :as cstr]
            (rebecca.context [ops :refer [default-agent default-speaker make-prompt
                                          context +facts +input epsilon-extend]]
                             [concat :refer [ccat]])
            [rebecca.completions.openai :as roai]
            [telegrambot-lib.core :as tbot])
  (:import java.time.Instant))

(def bot (tbot/create))

(defn send-reply
  [[reply ctxt] chat-id message-id]
  (do
    (tbot/send-message bot {:chat_id chat-id
                            :reply_to_message_id message-id
                            :text (:text reply)})
    (prn reply (:tokens (meta ctxt)))
    ctxt))

(defn reply-extend
  [ctxt messages]
  (reduce (fn [c m]
            (let [{ctime :date
                   message-id :message_id
                   {chat-id :id} :chat
                   {speaker :first_name :or {speaker "System"}} :from
                   msg-text :text :or {msg-text ""}} m]
              (-> c
                  (+input msg-text :timestamp (Instant/ofEpochSecond ctime)
                          :speaker speaker)
                  (epsilon-extend roai/davinci-3-complete
                                  :temperature 0.8 :max_tokens 1024)
                  (send-reply chat-id message-id))))
          ctxt
          (filter (fn [m] (not (or (nil? (:text m))
                                   (< (:date m)
                                      (.getEpochSecond (:end-time ctxt (Instant/MIN)))))))
                  (map :message messages))))

(def payload (atom query-updates))

(defn set-payload
  [f]
  (swap! payload (fn [a] f)))

(defn query-updates
  [last-read ctxt]
  (let [{status :ok results :result error :error}
        (tbot/get-updates bot {:offset last-read
                               :allowed_updates ["message"]})]
    (if (or status error)
      (if (= 0 (count results))
        [last-read ctxt]
        [(+ 1 (:update_id (last results))) (reply-extend ctxt results)])
      (do (prn "Error")
          [last-read ctxt]))))

(defn terminate
  [& args]
  (set-payload query-updates)
  (.interrupt (Thread/currentThread)))

(def saved-context (atom (context "Rebecca is a GPT-based general-purpose multi-lingual
chatbot currently undergoing alpha-testing with a small group of users. In her
current state, she doesn't have a long-lasting memory, and reboots will make her
forget all previous conversations. Once the initial testing phase is complete,
she will be given a persistent memory and more powerful logical abilities.
In addition, she can only see messages sent directly to her."
                                  :agent "Rebecca"
                                  :participants "a group of alpha-testers"
                                  :tlim 3096)))

(defn set-context
  [c]
  (swap! saved-context (fn [a] c)))

(defn store-context
  [last-read ctxt]
  (set-context ctxt)
  (set-payload query-updates)
  [last-read ctxt])

(defn load-context
  [last-read ctxt]
  (set-payload query-updates)
  [last-read @saved-context])

(defn polling-loop
  [last-read ctxt]
  (do
    (Thread/sleep 5000)
    (let [[new-read new-ctxt] (@payload last-read ctxt)]
      (recur new-read new-ctxt))))

(defn main [] (a/thread (polling-loop -1 @saved-context)))
