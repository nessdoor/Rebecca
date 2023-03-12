(ns rebecca.core
  (:require [wkok.openai-clojure.api :as oai])
  (:import (java.time DateTimeException Instant)
           java.time.temporal.ChronoUnit))

(def default-speaker "Other")

(def default-agent "Rebecca")

(def default-parameters {:model "text-davinci-003"
                         :temperature 0})

(defn context
  [intro & {:keys [participants agent]
            :or {participants default-speaker agent default-agent}}]
  (let [init-text
        (str intro
             "\nWhat follows is a conversation between " agent " and " participants ".")]
    (with-meta
      ;; The context itself
      {:agent-name agent
       :text init-text                    ; Introductory text
       :last-modified-time (Instant/now)} ; Timestamp of last received/produced info
      ;; Metadata
      {:primer (count init-text)          ; Length of the introductory text
       :segments                          ; Queue containing the length of each discrete message
       clojure.lang.PersistentQueue/EMPTY})))

(defn update-context-meta
  [ctxt-meta segment]
  (let [ctxt-queue (ctxt-meta :segments)
        new-text (segment :text)]       ; New text contained in the segment
    (merge ctxt-meta
           ;; Enqueue length of new text
           {:segments (conj ctxt-queue (count new-text))})))

(defn updated-context-time
  [ctxt-time seg-time]
  (when seg-time        ; Some segments are timeless
    ;; Disallow updates that alter the chronological history
    (if (.isBefore seg-time ctxt-time)
      (throw (new DateTimeException
                  (format "Appended information is older than context (%s < %s)"
                          seg-time ctxt-time)))
      {:last-modified-time seg-time})))

(defn ccat
  [ctxt segment]
  (let [{ctxt-text :text
         ctxt-time :last-modified-time} ctxt
        {seg-text :text
         seg-time :creation-time} segment]
    ;; Generate a new context with updated metadata
    (vary-meta
     (merge ctxt
            {:text (str ctxt-text "\n" seg-text)}      ; Concatenate new text to context
            (updated-context-time ctxt-time seg-time)) ; Update contextual timestamp
     update-context-meta segment)))                    ; Generate updated metadata through helper function

(defn +facts
  [ctxt facts]
  (let [{:keys [agent-name]} ctxt]
    (ccat ctxt
          {:text (str agent-name " knows that: " facts)})))

(defn make-prompt
  ([speaker] (make-prompt speaker (Instant/now)))
  ([speaker instant]
   (format "[%s|%s]:" speaker (.truncatedTo instant ChronoUnit/MINUTES))))

(defn +input
  [ctxt input & {ctime :creation-time sp :speaker
                 :or {ctime (Instant/now) sp default-speaker}}]
  (ccat ctxt {:text (str (make-prompt sp ctime) input)
              :creation-time ctime}))

(defn try-complete
  [text & {:as model-params}]
  (oai/create-completion
   (merge default-parameters model-params {:prompt text})))

(defn epsilon-extend
  [ctxt & {:as model-params}]
  (let [{aname :agent-name ctext :text} ctxt
        nowt (Instant/now)
        aprompt (make-prompt aname nowt)        ; Chat prompt for the agent
        tbc (str ctext aprompt)                 ; Text sent to the API
        answer (try-complete tbc model-params)  ; Answer from the API
        {:keys [choices]} answer                ; Completion candidates
        {completion :text} (first choices)]     ; First completion text candidate
    ;; First return value is the completion, while second is the extended context
    [completion
     (ccat ctxt {:text (str aprompt completion) :creation-time nowt})]))
