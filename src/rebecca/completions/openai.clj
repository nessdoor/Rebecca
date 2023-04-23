(ns rebecca.completions.openai
  (:require [clojure.string :as cstr]
            [cheshire.core :as chc]
            [wkok.openai-clojure.api :as oai]
            [java-time.api :as jt]
            [rebecca.history :as rh]
            [rebecca.completions.common :as cc]))

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
  (let [{agent-name :agent msgs :messages} ctxt
        tokens (:tokens (meta ctxt)     ; Known number of tokens in this context
                        (count msgs))   ; Assume at least 1 token per message
        fmt ((:formatter backend) ctxt) ; Formatted context
        {:keys [token-limit]} backend]
    (if (> tokens token-limit)
      ;; Trim context if we already know that it is too long
      (recur (cc/trim-context ctxt (:tokenizer backend) token-limit)
             backend model-params)
      ;; Else, try generating a reply
      (let [{:keys [reply response error]} ; Reply message, API response and possible error
            ((:generator backend) agent-name fmt)]
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

(defn davinci-3-format
  [& {pre :preamble msgs :messages}]
  (let [k (fn [{:keys [speaker timestamp text] :or {speaker "System"}}]
            (str (cc/msg-header speaker timestamp) text))]
    (concat (list pre) (map k msgs))))

(defn davinci-3-tokenize
  [ctxt]
  (let [fmt (davinci-3-format ctxt)]
    (map #(cc/default-token-estimator %) fmt)))

(defn davinci-3-complete
  [agent-name messages & {:as model-params}]
  (try                                 ; Completion may fail for various reasons
    (let [ctime (jt/instant)
          header (cc/msg-header agent-name ctime) ; Stimulates response from model
          response (oai/create-completion
                    (merge
                     default-parameters
                     model-params     ; User-supplied parameters
                     {:model "text-davinci-003"
                      :prompt (cstr/join messages
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

(defn system-time-msg [time]
  {:role "system"
   :content (format "Time:%s" (jt/truncate-to time :seconds))})

(defn gpt-35-chat-format
  [& {agent-name :agent pre :preamble msgs :messages}]
  (let [k (fn [msg]
            (let [{:keys [speaker text timestamp] :or {speaker "System"}} msg
                  header (cc/msg-header speaker timestamp)]
              (cond
                (= agent-name speaker) {:role "assistant" :content (str header text)}
                (nil? speaker) {:role "system" :content (str header text)}
                :else {:role "user" :content (str header text)})))]
    (concat (list {:role "assistant" :content pre})
            (map k msgs))))

(defn gpt-35-chat-tokenize
  [ctxt]
  (let [fmt (gpt-35-chat-format ctxt)]
    (map #(+ (cc/default-token-estimator (:content %)) 4) fmt)))

(defn gpt-35-chat-complete
  [agent-name messages & {:as model-params}]
  (try                                 ; Completion may fail for various reasons
    (let [ctime (jt/instant)           ; Timestamp of the response message
          response                     ; API response object
          (oai/create-chat-completion
           (merge default-parameters
                  model-params
                  {:model "gpt-3.5-turbo"
                   ;; Prompt is the concatenation of history and system time msg
                   :messages
                   (vec (concat messages
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
