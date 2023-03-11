(ns rebecca.core
  (:require [wkok.openai-clojure.api :as oai])
  (:import java.time.Instant))

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
      {:model agent :text init-text}     ; The context itself
      {:primer (count init-text)         ; Length of the introductory text
       :last-modified-time (Instant/now) ; Timestamp of last input/output
       :segments              ; Queue containing the length of each discrete message
       clojure.lang.PersistentQueue/EMPTY})))

(defn update-context-meta
  [ctxt-meta segment]
  (let [seg-queue (ctxt-meta :segments)
        new-text (segment :text)        ; New text contained in the segment
        seg-meta (meta segment)]
    (merge ctxt-meta
           ;; Enqueue length of the new text
           {:segments (conj seg-queue (count new-text))}
           ;; Eventually update modification time with segment creation time
           (when (contains? seg-meta :creation-time)
             {:last-modified-time (seg-meta :creation-time)}))))

(defn ccat
  [ctxt segment]
  (let [prev-text (ctxt :text)        ; Text currently being part of the context
        seg-text (segment :text)]     ; New text to be added
    ;; Generate a new context with updated metadata
    (vary-meta
     (assoc ctxt
            :text (str prev-text seg-text)) ; Concatenate new text to context
     update-context-meta segment)))         ; Generate updated metadata with helper function

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
