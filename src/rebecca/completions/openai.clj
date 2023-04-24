(ns rebecca.completions.openai
  (:require [clojure.string :as cstr]
            [cheshire.core :as chc]
            [clojure.core.cache.wrapped :as wcache]
            [wkok.openai-clojure.api :as oai]
            [java-time.api :as jt]
            [rebecca.history :as rh]
            [rebecca.completions.common :as cc]))

;;; Model-agnostic logic

(def default-parameters {:temperature 0})

(defn- get-error
  [e]
  (:error
   (chc/parse-string (:body (ex-data e))
                     true)))

(defn- length-exceeded?
  [e]
  (let [{:keys [type code]} (get-error e)]
    (and type code
         (= type "invalid_request_error")
         (= code "context_length_exceeded"))))

(defn gen-reply
  [ctxt backend & {:as model-params}]
  (let [{msgs :messages} ctxt
        tokens (:tokens (meta ctxt)     ; Known number of tokens in this context
                        (count msgs))   ; Assume at least 1 token per message
        {:keys [token-limit]} backend]
    (if (> tokens token-limit)
      ;; Trim context if we already know that it is too long
      (recur (cc/trim-context ctxt (:tokenizer backend) token-limit)
             backend model-params)
      ;; Else, try generating a reply
      (let [{:keys [reply response error]} ; Reply message, API response and possible error
            (apply (:generator backend) ctxt model-params)]
        (if error                       ; If we received an error...
          (if (length-exceeded? error)  ; ... and it is an overflow exception...
            (recur (cc/trim-context     ; ... trim history and retry
                    ctxt (:tokenizer backend) token-limit)
                   backend model-params)
            (throw error))              ; Re-throw any other exception
          ;; If no error happened, return reply message and extended context
          [reply
           (vary-meta
            (rh/h-conj ctxt reply)
            ;; Remember token count of resulting context for future overflow tests
            assoc :tokens (get-in response [:usage :total_tokens]))])))))

(def formatted-context-cache (wcache/fifo-cache-factory {} :threshold 3))
(def formatted-message-cache (wcache/fifo-cache-factory {}))

(defn- with-backend-key [e k] [e k])
(defn- unwrap-backend-key [f e] (f (first e)))

;;; Model-specific logic

;;; text-davinci-003

(defn davinci-3-format
  [ctxt]
  (wcache/lookup-or-miss
   ;; Cache final result of the formatting
   formatted-context-cache (with-backend-key ctxt :davinci-3)
   unwrap-backend-key
   (fn [ctxt]
     (let [{pre :preamble msgs :messages} ctxt
           k (fn [msg]
               ;; Cache per-message expansion
               (wcache/lookup-or-miss
                formatted-message-cache (with-backend-key msg :davinci-3)
                unwrap-backend-key
                (fn [msg]
                  (let  [{:keys [speaker timestamp text]
                          :or {speaker "System"}} msg]
                    (str (cc/msg-header speaker timestamp) text)))))]
       (concat (list pre) (map k msgs))))))

(defn davinci-3-tokenize
  [ctxt]
  (let [fmt (davinci-3-format ctxt)]
    (map #(cc/default-token-estimator %) fmt)))

(defn davinci-3-complete
  [ctxt & {:as model-params}]
  (try                                 ; Completion may fail for various reasons
    (let [ctime (jt/instant)
          {agent-name :agent} ctxt
          header (cc/msg-header agent-name ctime) ; Stimulates response from model
          response (oai/create-completion
                    (merge
                     default-parameters
                     model-params     ; User-supplied parameters
                     {:model "text-davinci-003"
                      :prompt (cstr/join (davinci-3-format ctxt)
                                         (list header))}))]
      ;; Return reply message and full API response
      {:reply (rh/message (:text (first (:choices response)))
                          :speaker agent-name :timestamp ctime)
       :response response})
    (catch Exception e {:error e})))    ; Upon failure, return the exception

(def davinci-3
  {:formatter davinci-3-format
   :tokenizer davinci-3-tokenize
   :generator davinci-3-complete
   :token-limit 4097})

;;; gpt-3.5-turbo

(defn system-time-msg [time]
  {:role "system"
   :content (format "Time:%s" (jt/truncate-to time :seconds))})

(defn gpt-35-chat-format
  [ctxt]
   ;; Cache final result of the formatting
  (wcache/lookup-or-miss
   formatted-context-cache (with-backend-key ctxt :gpt-35)
   unwrap-backend-key
   (fn [ctxt]
     (let [{agent-name :agent pre :preamble msgs :messages} ctxt
           k (fn [msg]
               ;; Cache per-message expansion
               (wcache/lookup-or-miss
                formatted-message-cache (with-backend-key msg :gpt-35)
                unwrap-backend-key
                (fn [msg]
                  (let [{:keys [speaker text timestamp] :or {speaker "System"}} msg
                        header (cc/msg-header speaker timestamp)]
                    (cond
                      (= agent-name speaker) {:role "assistant" :content (str header text)}
                      (nil? speaker) {:role "system" :content (str header text)}
                      :else {:role "user" :content (str header text)})))))]
       (concat (list {:role "assistant" :content pre})
               (map k msgs))))))

(defn gpt-35-chat-tokenize
  [ctxt]
  (let [fmt (gpt-35-chat-format ctxt)]
    (map #(+ (cc/default-token-estimator (:content %)) 4) fmt)))

(defn gpt-35-chat-complete
  [ctxt & {:as model-params}]
  (try                                 ; Completion may fail for various reasons
    (let [ctime (jt/instant)           ; Timestamp of the response message
          {agent-name :agent} ctxt
          response                     ; API response object
          (oai/create-chat-completion
           (merge default-parameters
                  model-params
                  {:model "gpt-3.5-turbo"
                   ;; Prompt is the concatenation of history and system time msg
                   :messages
                   (vec (concat (gpt-35-chat-format ctxt)
                                (list (system-time-msg ctime))))}))
          ;; Reply message
          completion (:content (:message (first (:choices response))))
          ;; The model could have tried to emulate the message header, and we
          ;; will have to remove it, if it did. Create a loose regexp matcher
          ;; for it, and then attempt a match.
          header-match (re-find
                        (re-pattern (str "^\\[" agent-name "\\|.*\\]:"))
                        completion)]
      ;; Return reply message and full API response
      {:reply
       (rh/message (if header-match     ; Remove the unwanted header, if present
                     (subs completion (count header-match))
                     completion) :speaker agent-name :timestamp ctime)
       :response response})
    (catch Exception e {:error e})))    ; Upon failure, return the exception

(def gpt-35-chat
  {:formatter gpt-35-chat-format
   :tokenizer gpt-35-chat-tokenize
   :generator gpt-35-chat-complete
   :token-limit 4096})
