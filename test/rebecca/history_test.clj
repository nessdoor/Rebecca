(ns rebecca.history-test
  (:require (rebecca [history :as sut]
                     [history-spec :as hs])
            [clojure.spec.alpha :as s]
            [clojure.test :as t]
            [clojure.test.check :as tc]
            (clojure.test.check [generators :as gen]
                                [properties :as prop]
                                [clojure-test :as ct])))

;;; Verify conjoining of segments unto histories
(ct/defspec conjoining
  {:num-tests 100
   :max-size 50}
  (prop/for-all [[hist comps]
                 (gen/let [{end :end-time :as hist} (s/gen :rebecca/history)
                           ;; Generate messages that are newer than history
                           comps (gen/list
                                  (gen/such-that
                                   #(let [{ts :timestamp} %]
                                      (or (nil? end)
                                          (= end ts) (.isBefore end ts)))
                                   (s/gen :rebecca/message) 50))]
                   ;; Make sure that the list of messages is sorted
                   [hist (sort-by :timestamp comps)])]
                (let [res (apply sut/h-conj hist comps)] ; Sample
                  (and
                   ;; New elements should have been appended
                   (= (:messages res)
                      (concat (:messages hist) comps))
                   ;; Start time must be left unchanged, if it existed,
                   ;; otherwise is that of the first message
                   (= (:start-time res)
                      (:start-time hist (:timestamp (first comps))))
                   ;; End time is that of the last appended message, if any
                   (= (:end-time res)
                      (:timestamp (last comps) (:end-time hist)))))))

;;; Verify history concatenation
(ct/defspec concatenation
  {:num-tests 100
   :max-size 50}
  (prop/for-all [[{s1 :start-time e1 :end-time :as h1}
                  {s2 :start-time e2 :end-time :as h2}]
                 (gen/let [{end :end-time :as h1} (s/gen :rebecca/history)
                           ;; Generate only messages that are later than h1
                           comps (gen/list
                                  (gen/such-that
                                   #(let [{ts :timestamp} %]
                                      (or (nil? end)
                                          (= end ts)
                                          (.isBefore end ts)))
                                   (s/gen :rebecca/message) 70))]
                   ;; Create h2 from such messages, so that it is later than h1
                   [h1 (apply sut/h-conj
                              sut/EMPTY
                              (sort-by :timestamp comps))])]
                (let [res (sut/h-concat h1 h2)] ; Sample
                  ;; Time ranges either absent or they have been concatenated
                  (or (not (or s1 s2))
                      (= (:start-time res)
                         (:start-time h1 (:start-time h2))))
                  (or (not (or e1 e2))
                      (= (:end-time res)
                         (:end-time h2 (:end-time h1))))
                  ;; Messages have been concatenated
                  (= (:messages res)
                     (concat (:messages h1) (:messages h2))))))
