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
      {:primer [0 (count init-text)]     ; Text range containing the intro
       :last-modified-time (Instant/now) ; Timestamp of last input/output
       :segments              ; Queue containing the ranges of discrete messages
       clojure.lang.PersistentQueue/EMPTY})))

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
