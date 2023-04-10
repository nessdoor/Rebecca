(ns rebecca.context
  (:require [clojure.string :as cstr]
            [clojure.math :refer [round]]
            [clojure.spec.alpha :as s]
            [rebecca.history :refer [h-concat h-conj]])
  (:import java.time.Instant
           java.time.temporal.ChronoUnit))

(def default-speaker "Other")

(def default-agent "Rebecca")

(def default-token-estimator (fn [seg-text]
                               (round
                                (* 4/3
                                   (count (cstr/split seg-text #"\s+"))))))

(def default-token-limit 2048)

(def default-trim-factor 3/4)

(defn make-prompt
  ([speaker] (make-prompt speaker (Instant/now)))
  ([speaker instant]
   (format "[%s|%s]:" speaker (.truncatedTo instant ChronoUnit/SECONDS))))

(defn context
  [text & {:keys [participants agent tlim trim-fact testim]
            :or {participants default-speaker
                 agent default-agent
                 tlim default-token-limit
                 trim-fact default-trim-factor
                 testim default-token-estimator}}]
  (let [preamble
        (str text
             "\nWhat follows is a conversation between " agent " and " participants ".")
        pre-toks (testim preamble)]
    (with-meta
      ;; The context itself
      {:agent agent               ; Agent name
       :preamble preamble}        ; Introductory text
      ;; Metadata
      {:tokens pre-toks           ; Length in tokens of the whole context
       :tokens-limit tlim         ; Hard limit on the number of tokens
       :trim-factor trim-fact     ; Proportion of context to keep after trimming
       :tokens-estimator testim   ; Number-of-tokens estimator
       :pre-toks pre-toks})))     ; Length of the introductory text

(defn h-trim
  [h]
  {:pre [(s/valid? :rebecca/history h)
         (contains? (meta h) :tokens)
         (contains? (meta h) :tokens-limit)]
   :post [(s/valid? :rebecca/history %)
          (= (dissoc (meta h) :tokens)
             (dissoc (meta %) :tokens))
          (let [{:keys [tokens tokens-limit]} (meta %)]
            (<= tokens tokens-limit))]}
  (let [{:keys [tokens tokens-limit trim-factor]
         :or {trim-factor default-trim-factor}} (meta h)
        {:keys [messages]} h]
    ;; Pop from history until we have recouped enough tokens
    (loop [cmps messages
           tgoal (- tokens (* tokens-limit trim-factor))
           recouped 0]
      (if (< recouped tgoal)
        (let [ctok (:tokens (meta (peek cmps)))]
          (recur (pop cmps) tgoal (+ recouped ctok)))
        ;; Once the goal is reached, recreate history from the shortened queue
        (vary-meta
         (let [new-hist (assoc h :messages cmps)
               new-start (:timestamp (peek cmps))]
           (if (nil? new-start)
             ;; History is now empty, delete all time references
             (dissoc new-hist :start-time :end-time)
             ;; Start time equal to the timestamp of the 1st message
             (assoc new-hist :start-time new-start)))
         merge {:tokens (- tokens recouped)})))))

(defn +facts
  [hist facts]
  (let [{agent :agent testim :tokens-estimator
         :or {agent default-agent testim default-token-estimator}} hist]
    (h-conj hist
            (let [text (str agent " knows that: " facts)]
              (with-meta
                {:text text
                 :timestamp (Instant/now)}
                {:tokens (testim text)})))))

(defn +input
  [hist input & {ctime :timestamp sp :speaker
                 :or {ctime (Instant/now) sp default-speaker}}]
  (let [isp (.intern sp)                ; Speaker string is highly-repetitive
        prompt (make-prompt isp ctime)
        expa (str prompt input)
        {testim :tokens-estimator
         :or {testim default-token-estimator}} (meta hist)]
    (h-conj hist
            (with-meta {:speaker isp
                        ;; Reuse expansion for representing the input text
                        :text (subs expa (count prompt))
                        :timestamp ctime}
              {:expansion expa            ; Caching expansion speeds up concat
               :tokens (testim expa)}))))

(defn epsilon-extend
  [ctxt compl-backend & {:as model-params}]
  (let [answer (compl-backend ctxt model-params)] ; Answer from the API
    ;; First return value is the completion, while second is the extended context
    [answer (h-conj ctxt answer)]))
