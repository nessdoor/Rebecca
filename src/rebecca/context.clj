(ns rebecca.context
  (:require [clojure.string :as cstr])
  (:import (java.time DateTimeException Instant)
           java.time.temporal.ChronoUnit))

(def default-speaker "Other")

(def default-agent "Rebecca")

(def default-token-limit 2048)

(def default-trim-factor 3/4)

(def default-token-estimator (fn [seg-text]
                               (* 4/3
                                  (count (cstr/split seg-text #"\s+")))))

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

(defn update-context-meta
  [ctxt-meta segment]
  (let [{segq :segments
         ctok :tokens
         testim :tokens-estimator} ctxt-meta
        seg-text (segment :text)      ; New text contained in the segment
        {seg-tokens :tokens           ; (Estimated) number of tokens in new text
         :or {seg-tokens (testim seg-text)}} (meta segment)]
    (merge ctxt-meta
           ;; Enqueue length of new text (chars and tokens)
           {:segments (conj segq [(count seg-text) seg-tokens])
            ;; Increase overall token count
            :tokens (+ ctok seg-tokens)})))

(defn updated-context-time
  [ctxt-time seg-time]
  (when seg-time        ; Some segments are timeless
    ;; Disallow updates that alter the chronological history
    (if (.isBefore seg-time ctxt-time)
      (throw (new DateTimeException
                  (format "Appended information is older than context (%s < %s)"
                          seg-time ctxt-time)))
      {:last-modified-time seg-time})))

(defn pop-segments
  [queue char-acc toks-count toks-limit]
  (if (<= toks-count toks-limit)
    [char-acc toks-count queue]         ; If lower limit reached, return
    (let [[ch toks] (peek queue)]       ; Otherwise, pop segment and recur
      (recur (pop queue) (+ char-acc ch) (+ toks-count toks) toks-limit))))

(defn trim-history
  [ctxt]
  (let [cmeta (meta ctxt)
        [cut-chars trimmed-len segq]
        (let [{:keys [segments tokens tokens-limit trim-factor]} cmeta]
          (pop-segments segments 0 tokens (* tokens-limit trim-factor)))]
    (vary-meta
     (assoc ctxt
            ;; Truncate history by cut-chars, leaving the primer intact
            {:text (let [primlen (:primer cmeta) ctext (:text ctxt)]
                     (str (subs ctext 0 primlen)
                          (subs ctext (+ primlen cut-chars))))})
     merge
     {:tokens trimmed-len :segments segq})))

(defn ccat-unsafe
  [ctxt segment]
  ;; Generate a new context with updated metadata
  (vary-meta
   (merge ctxt
          ;; Concatenate new text to context
          {:text (str (ctxt :text) "\n" (segment :text))}
          ;; Update context timestamp
          (updated-context-time (ctxt :last-modified-time)
                                (segment :creation-time)))
   ;; Generate updated metadata through helper function
   update-context-meta segment))

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

(defn make-prompt
  ([speaker] (make-prompt speaker (Instant/now)))
  ([speaker instant]
   (format "[%s|%s]:" speaker (.truncatedTo instant ChronoUnit/MINUTES))))

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
