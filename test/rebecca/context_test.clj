(ns rebecca.context-test
  (:require (rebecca [context :as sut]
                     [context-spec :as cs]
                     [history-spec :as hs])
            [clojure.spec.alpha :as s]
            [clojure.test :as t]
            [clojure.test.check :as tc]
            (clojure.test.check [generators :as gen]
                                [properties :as prop]
                                [clojure-test :as ct])))

;;; Generator of components with metadata
(def component-gen (gen/let [component (s/gen :rebecca/component)
                             cmeta (s/gen :rebecca.component/meta)]
                     (with-meta component cmeta)))

;;; Generator of histories with consistent token metadata
(def history-gen (gen/let [comps (gen/fmap #(sort-by :timestamp %)
                                           (gen/list component-gen))
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
                   (= (drop-while #(not= (peek (:components sh)) %)
                                  (:components lh))
                      (:components sh))))))
