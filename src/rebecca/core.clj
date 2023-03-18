(ns rebecca.core
  (:require [wkok.openai-clojure.api :as oai]
            (rebecca.context [ops :refer [default-agent default-speaker
                                          context +facts +input epsilon-extend]]
                             [concat :refer [ccat]])
            [telegrambot-lib.core :as tbot])
  (:import java.time.Instant))

(def default-parameters {:model "text-davinci-003"
                         :temperature 0})

(defn try-complete
  [text & {:as model-params}]
  (oai/create-completion
   (merge default-parameters model-params {:prompt text})))

(def bot (tbot/create))

(defn send-reply
  [[reply ctxt] chat-id message-id]
  (do
    (tbot/send-message bot {:chat_id chat-id
                            :reply_to_message_id message-id
                            :text reply})
    (prn reply "\n" (:tokens (meta ctxt)))
    ctxt))

(defn reply-extend
  [ctxt messages]
  (reduce (fn [c m]
            (let [{ctime :date
                   message-id :message_id
                   {chat-id :id} :chat
                   {speaker :first_name} :form
                   msg-text :text} m]
              (-> c
                  (+input msg-text :creation-time (Instant/ofEpochSecond ctime)
                          :speaker speaker)
                  (epsilon-extend try-complete :temperature 0.8 :max_tokens 256)
                  (send-reply chat-id message-id))))
          ctxt
          (filter (fn [m] (not (or (nil? (:text m))
                                   (< (:date m)
                                      (.getEpochSecond (:last-modified-time ctxt))))))
                  (map :message messages))))

(defn polling-loop
  [last-read ctxt]
  (do
    (Thread/sleep 5000)
    (let [{status :ok results :result error :error}
          (tbot/get-updates bot {:offset last-read
                                 :allowed_updates ["message"]})]
      (if (or status error)
        (if (= 0 (count results))
          (recur last-read ctxt)
          (recur (+ 1 (:update_id (last results)))
                 (reply-extend ctxt results)))
        (do (prn "Error")
            (recur last-read ctxt))))))

(defn main
  []
  (let [context (context "Rebecca is a GPT-based general-purpose multi-lingual
chatbot currently undergoing alpha-testing with a small group of users. In her
current state, she doesn't have a long-lasting memory, and reboots will make her
forget all previous conversations. Once the initial testing phase is complete,
she will be given a persistent memory and more powerful logical abilities.
In addition, she can only see messages sent directly to her."
                         :agent "Rebecca"
                         :participants "a group of alpha-testers"
                         :tlim 3096)]
    (polling-loop -1 context)))
