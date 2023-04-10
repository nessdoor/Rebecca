(ns rebecca.completions.openai
  (:require [clojure.string :as cstr]
            [wkok.openai-clojure.api :as oai]
            [rebecca.context :refer [make-prompt]])
  (:import java.time.Instant
           java.time.temporal.ChronoUnit))

(def default-parameters {:temperature 0})

(defn davinci-3-complete
  [ctxt & {:as model-params}]
  (let [{agent :agent pre :preamble comp :messages} ctxt
        ctime (Instant/now)
        footer (make-prompt agent ctime) ; Chat prompt that stimulates response
        result (oai/create-completion
                (merge
                 default-parameters
                 model-params
                 {:model "text-davinci-003"}
                 ;; Prompt is the concatenation of primer, history and footer
                 {:prompt (cstr/join
                           (concat (list pre)
                                   ;; Use the cached expansions to build prompt
                                   (map (fn [c] (:expansion (meta c))) comp)
                                   (list footer)))}))
        ;; Concatenate footer with the first completion to obtain final text
        completion (str footer (:text (first (:choices result))))]
    (with-meta
      {:speaker agent :text (subs completion (count footer)) :timestamp ctime}
      ;; Adjust token usage estimation error with the response from the API
      {:tokens (- (:total_tokens (:usage result))
                  (:tokens (meta ctxt)))
       :expansion completion})))

(defn system-time-msg [time]
  {:role "system"
   :content (format "Time:%s"
                    (.truncatedTo time ChronoUnit/SECONDS))})

(defn to-chat-format
  [agent-name msg]
  (let [{:keys [speaker text timestamp]} msg
        {exp :expansion
         :or {exp (str (make-prompt speaker timestamp))}} (meta msg)]
    (cond
      ;; Every message from the agent is preceded by a system timestamp
      (= agent-name speaker) [(system-time-msg timestamp)
                              {:role "assistant" :content text}]
      (nil? speaker) [{:role "system" :content text}]
      :else [{:role "user" :content exp}])))

(defn gpt-35-chat
  [ctxt & {:as model-params}]
  (let [{agent :agent pre :preamble comp :messages} ctxt
        ctime (Instant/now)
        result (oai/create-chat-completion
                (merge
                 default-parameters
                 model-params
                 {:model "gpt-3.5-turbo"}
                 ;; Prompt is the concatenation of primer, history and footer
                 {:messages
                  (vec (concat
                        (list {:role "system" :content pre})
                        ;; Format messages into chat messages
                        (mapcat (fn [m] (to-chat-format agent m)) comp)
                        (list (system-time-msg ctime))))}))
        ;; Concatenate footer with the first completion to obtain final text
        completion (:content (:message (first (:choices result))))]
    (with-meta
      {:speaker agent :text completion :timestamp ctime}
      ;; Adjust token usage estimation error with the response from the API
      {:tokens (- (:total_tokens (:usage result))
                  (:tokens (meta ctxt)))})))
