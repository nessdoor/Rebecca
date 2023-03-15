(ns rebecca.context
  (:require [clojure.string :as cstr]
            [rebecca.conc-hist :refer [ccat-unsafe trim-history]])
  (:import java.time.Instant
           java.time.temporal.ChronoUnit))

(def default-speaker "Other")

(def default-agent "Rebecca")

(def default-token-limit 2048)

(def default-trim-factor 3/4)

(def default-token-estimator (fn [seg-text]
                               (* 4/3
                                  (count (cstr/split seg-text #"\s+")))))

(defn make-prompt
  ([speaker] (make-prompt speaker (Instant/now)))
  ([speaker instant]
   (format "[%s|%s]:" speaker (.truncatedTo instant ChronoUnit/MINUTES))))

(defn context
  [intro & {:keys [participants agent tlim trim-fact testim]
            :or {participants default-speaker
                 agent default-agent
                 tlim default-token-limit
                 trim-fact default-trim-factor
                 testim default-token-estimator}}]
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
       :tokens (testim init-text)         ; Length in tokens of the whole context
       :tokens-limit tlim                 ; Hard limit on the number of tokens
       :trim-factor trim-fact             ; Proportion of context to keep after trimming
       :tokens-estimator testim           ; Number-of-tokens estimator
       :segments                          ; Queue containing the length (chars and tokens) of each discrete message
       clojure.lang.PersistentQueue/EMPTY})))

(defn ccat
  [ctxt segment]
  (let [new-ctxt (ccat-unsafe ctxt segment) ; Unchecked segment concatenation
        {ctok :tokens ctlim :tokens-limit} (meta new-ctxt)]
    ;; If the total number of tokens surpassed the limit, trim history
    (if (> ctok ctlim)
      (trim-history new-ctxt)
      new-ctxt)))

(defn +facts
  [ctxt facts]
  (let [{:keys [agent-name]} ctxt]
    (ccat ctxt
          {:text (str agent-name " knows that: " facts)})))

(defn +input
  [ctxt input & {ctime :creation-time sp :speaker
                 :or {ctime (Instant/now) sp default-speaker}}]
  (ccat ctxt {:text (str (make-prompt sp ctime) input)
              :creation-time ctime}))

(defn epsilon-extend
  [ctxt compl-backend & {:as model-params}]
  (let [{aname :agent-name ctext :text} ctxt
        nowt (Instant/now)
        aprompt (make-prompt aname nowt)        ; Chat prompt for the agent
        tbc (str ctext aprompt)                 ; Text sent to the API
        answer (compl-backend tbc model-params) ; Answer from the API
        {:keys [choices]} answer                ; Completion candidates
        {completion :text} (first choices)]     ; First completion text candidate
    ;; First return value is the completion, while second is the extended context
    [completion
     (ccat ctxt {:text (str aprompt completion) :creation-time nowt})]))
