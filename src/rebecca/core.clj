(ns rebecca.core
  (:require [wkok.openai-clojure.api :as oai])
  (:import java.time.Instant
           java.time.DateTimeException))

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
      {:model agent                       ; Name of the agent
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
  [history facts] (str default-agent " knows that: " facts))

(defn +input
  ([history input] (+input history default-speaker input))
  ([history speaker input] (str history (format "\n[%s]:%s" speaker input))))

(defn epsilon-extend
  [history & {:as model-params}]
  (oai/create-completion
   (merge default-parameters
          model-params
          {:prompt (str history
                        (format "\n[%s]:" default-agent))})))

(defn get-reply
  [context input & {:keys [speaker] :or {speaker default-speaker} :as extra}]
  (->
   context
   (+input speaker input)
   (epsilon-extend
    (dissoc extra :speaker))))         ; Pass extra parameters to API w/o :speaker
