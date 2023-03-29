(ns rebecca.context.history-test
  (:require (rebecca.context [history :as sut]
                             [spec :as cs])
            [clojure.spec.alpha :as s]
            [clojure.test :as t]
            [clojure.test.check :as tc]
            (clojure.test.check [generators :as gen]
                                [properties :as prop]
                                [clojure-test :as ct])))

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
                     (s/gen :rebecca/history) 30)] ; Usually 30 tries suffice
                (let [sh (sut/h-trim lh)]
                  (and
                   ;; First property: history has been shortened
                   (< (:tokens (meta sh)) (:tokens-limit (meta sh)))
                   ;; Second property: result is a sub-sequence of the original
                   (= (drop-while #(not= (peek (:components sh)) %)
                                  (:components lh))
                      (:components sh))))))
