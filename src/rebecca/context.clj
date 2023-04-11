(ns rebecca.context
  (:require [rebecca.history :refer [h-concat h-conj]])
  (:import java.time.Instant))

(def default-speaker "Other")

(def default-agent "Rebecca")

(defn context
  [text & {:keys [participants agent]
            :or {participants default-speaker
                 agent default-agent}}]
  (let [preamble
        (str text
             "\nWhat follows is a conversation between "
             agent " and " participants ".")]
    {:agent agent :preamble preamble}))

(defn +facts
  [hist facts]
  (let [{agent :agent :or {agent default-agent}} hist]
    (h-conj hist
            {:text (str agent " knows that: " facts)
             :timestamp (Instant/now)})))

(defn +input
  [hist input & {ctime :timestamp sp :speaker
                 :or {ctime (Instant/now) sp default-speaker}}]
  (h-conj hist {:speaker (.intern sp)   ; Speaker string is highly-repetitive
                :text input :timestamp ctime}))

(defn epsilon-extend
  [ctxt compl-backend & {:as model-params}]
  (let [answer (compl-backend ctxt model-params)] ; Answer from the API
    ;; First return value is the completion, while second is the extended context
    [answer (h-conj ctxt answer)]))
