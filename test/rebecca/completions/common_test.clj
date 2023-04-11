(ns rebecca.completions.common-test
  (:require [rebecca.completions.common :as sut]
            [rebecca.history-spec :as hs]
            [clojure.spec.alpha :as s]
            [clojure.test :as t]
            [clojure.test.check :as tc]
            (clojure.test.check [generators :as gen]
                                [properties :as prop]
                                [clojure-test :as ct])))

;;; Token count: number of tokens of which a certain text is composed of
(s/def :rebecca/tokens int?)

;;; Expansion: textual expansion of a message
(s/def :rebecca.message/expansion string?)

;;; Message metadata expected or produced by context manipulation code
(s/def :rebecca.message/meta (s/keys :req-un [:rebecca/tokens]
                                     :opt-un [:rebecca.message/expansion]))

;;; Token trimming parameters of contexts
(s/def :rebecca.history/tokens-limit pos-int?)
(s/def :rebecca.history/trim-factor (s/and number?
                                           #(< 0 % 1)))
(s/def :rebecca.history/tokens-estimator (s/fspec :args (s/cat :t string?)
                                                  :ret pos-int?))

;;; History metadata expected or produced by context manipulation code
(s/def :rebecca.history/meta (s/keys :req-un [:rebecca/tokens]
                                     :opt-un [:rebecca.history/tokens-limit
                                              :rebecca.history/trim-factor
                                              :rebecca.history/tokens-estimator]))

(s/def :rebecca.context/pre-toks pos-int?)
(s/def :rebecca.context/meta (s/merge :rebecca.history/meta
                                      (s/keys :req-un
                                              [:rebecca.context/pre-toks])))
;;; Generator of messages with metadata
(def message-gen (gen/let [message (s/gen :rebecca/message)
                           cmeta (s/gen :rebecca.message/meta)]
                     (with-meta message cmeta)))

;;; Generator of histories with consistent token metadata
(def history-gen (gen/let [comps (gen/fmap #(sort-by :timestamp %)
                                           (gen/list message-gen))
                           ;; This contains limits, estimators and trim factors
                           hmeta (s/gen :rebecca.history/meta)]
                   (with-meta (hs/history
                               (into clojure.lang.PersistentQueue/EMPTY comps))
                     (assoc hmeta :tokens
                            (reduce + (map #(:tokens (meta %)) comps))))))

;;; Verify that trimming:
;;; - reduces the token size beneath the limit
;;; - produces a history that is a subset of the starting history
(ct/defspec trimming
  {:num-tests 100                  ; Arbitrary number of test runs
   :max-size 50}                   ; Larger sizes may generate integer overflows
  (prop/for-all [lh (gen/such-that
                     ;; Construct histories with a token count above the limit
                     #(let [m (meta %)]
                        (and (:tokens-limit m)
                             (> (:tokens m) (:tokens-limit m))))
                     history-gen 30)]   ; Usually 30 tries will suffice
                (let [sh (sut/h-trim lh)]
                  (and
                   ;; First property: history has been shortened
                   (< (:tokens (meta sh)) (:tokens-limit (meta sh)))
                   ;; Second property: result is a sub-sequence of the original
                   (= (drop-while #(not= (peek (:messages sh)) %)
                                  (:messages lh))
                      (:messages sh))))))
