(ns rebecca.completions.openai
  (:require [clojure.string :as cstr]
            [cheshire.core :as chc]
            [wkok.openai-clojure.api :as oai]
            [rebecca.history :as rh]
            [rebecca.completions.common :as cc])
  (:import java.time.Instant
           java.time.temporal.ChronoUnit))

(def default-parameters {:temperature 0})

(defn gen-reply
  [ctxt backend & {:as model-params}]
  (let [{agent-name :agent msgs :messages} ctxt
        tokens (:tokens (meta ctxt)      ; Equivalent tokens of the context
                        (count msgs))    ; (At least 1 token per message)
        fmt ((:formatter backend) ctxt)  ; Formatted text messages
        msgs-tokens                      ; Tokens of each message
        ((:tokenizer backend) fmt)
        {:keys [token-limit]} backend]
    (if (> tokens token-limit)
      ;; Trim context if we know that it is too long
      (recur (cc/trim-context ctxt msgs-tokens token-limit)
             backend model-params)
      ;; Else, try to generate a reply
      (try
        (let [{:keys [reply response]}    ; Reply message and API response
              ((:generator backend) agent-name fmt)
              {ptoks :prompt_tokens       ; Token count of reply and context
               total :total_tokens} (:usage response)
              sized-reply (vary-meta reply assoc :tokens ptoks)]
          [sized-reply
           (vary-meta
            (rh/h-conj ctxt sized-reply)
            ;; Remember the total token count of the entire context
            assoc :tokens total)])
        (catch Exception e
          (let [{{:keys [type code]} :error} (chc/parse-string (:body (ex-data e)) true)]
            (if (and type code
                     (= type "invalid_request_error")
                     (= code "context_length_exceeded"))
              ;; Completion failed because context overflowed, trim and retry
              (gen-reply (cc/trim-context ctxt msgs-tokens token-limit)
                         backend model-params)
              ;; Else, re-throw
              (throw e))))))))

(defn davinci-3-format
  [& {pre :preamble msgs :messages}]
  (let [k (fn [{:keys [speaker timestamp text] :or {speaker "System"}}]
            (str (cc/msg-header speaker timestamp) text))]
    (concat (list pre) (map k msgs))))

(defn davinci-3-tokenize
  [fmt] (map #(cc/default-token-estimator %) fmt))

(defn davinci-3-complete
  [agent-name messages & {:as model-params}]
  (let [ctime (Instant/now)
        header (cc/msg-header agent-name ctime) ; Stimulates response from model
        response (oai/create-completion
                  (merge
                   default-parameters
                   model-params     ; User-supplied parameters
                   {:model "text-davinci-003"
                    :prompt (cstr/join messages
                                       (list header))}))]
    (if true                        ; TODO completion valid?
      (println response)
      (let [completion             ; Header + completion = response message text
            (str header (:text (first (:choices response))))
            reply (vary-meta            ; Response message w/ cached format
                   {:speaker agent-name
                    :text (subs completion (count header))
                    :timestamp ctime}
                   assoc :formatted completion)]
        ;; Return formatted reply message and full API response
        ;; TODO signal failure
        {:reply reply :response response}))))

(def davinci-3
  {:formatter davinci-3-format
   :tokenizer davinci-3-tokenize
   :generator davinci-3-complete
   :token-limit 4097})

(defn system-time-msg [time]
  {:role "system"
   :content (format "Time:%s"
                    (.truncatedTo time ChronoUnit/SECONDS))})

(defn gpt-35-chat-format
  [ctxt]
  (let [{agent-name :agent pre :preamble msgs :messages} ctxt
        k (fn [msg]
            (let [{:keys [speaker text timestamp] :or {speaker "System"}} msg
                  header (cc/msg-header speaker timestamp)]
              (cond
                (= agent-name speaker) {:role "assistant" :content (str header text)}
                (nil? speaker) {:role "system" :content (str header text)}
                :else {:role "user" :content (str header text)})))]
    (concat (list {:role "assistant" :content pre})
            (map k msgs))))

(defn gpt-35-chat-tokenize
  [fmt] (map #(+ (cc/default-token-estimator (:content %)) 4) fmt))

(defn gpt-35-chat-complete
  [agent-name messages & {:as model-params}]
  (let [ctime (Instant/now)
        response (oai/create-chat-completion
                (merge
                 default-parameters
                 model-params
                 {:model "gpt-3.5-turbo"
                 ;; Prompt is the concatenation of primer, history and footer
                  :messages
                  (vec (concat messages
                               (list (system-time-msg ctime))))}))]
    (if true                            ; TODO completion valid?
      (do
        (println response)
        (let [completion (:content (:message (first (:choices response))))
              ;; Due to the formatting, the model could generate an unwanted header
              header-match (re-find
                            (re-pattern (str "^\\[" agent-name "\\|.*\\]:"))
                            completion)]
          {:reply
           (rh/message (if header-match ; We cannot trust the model's output
                         (subs completion (count header-match)) ; Exclude header
                         completion)
                       :speaker agent-name :timestamp ctime)
           :response response})))))

(def gpt-35-chat
  {:formatter gpt-35-chat-format
   :tokenizer gpt-35-chat-tokenize
   :generator gpt-35-chat-complete
   :token-limit 4096})
