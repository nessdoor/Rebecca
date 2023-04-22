(ns rebecca.history-spec
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [java-time.api :as jt]
            [rebecca.seq :refer [queue queue?]])
  (:import java.time.Instant))

;;; Message: a textual piece of information said by someone
(s/def :rebecca.message/speaker string?)
(s/def :rebecca.message/text string?)
(s/def :rebecca.message/timestamp inst?)
(s/def :rebecca/message (s/keys :req-un [:rebecca.message/text
                                         :rebecca.message/timestamp]
                                :opt-un [:rebecca.message/speaker]))

;;; Persistent queue generator for property-based testing
(defn pqueue [generator] (gen/fmap #(into (queue) %) generator))

;;; Generate persistent queues of chronologically-ordered segments
(defn squeue-gen [] (pqueue (gen/fmap #(sort-by :timestamp %)
                                      (gen/list
                                       (s/gen :rebecca/message)))))

;;; History: a succession of messages
(s/def :rebecca.history/messages (s/with-gen
                                     (s/and
                                      (s/coll-of :rebecca/message :kind queue?)
                                      #(if (> (count %) 1)
                                         (apply jt/not-after?
                                                (map jt/instant
                                                     (map :timestamp %)))
                                         true))
                                     squeue-gen))
(s/def :rebecca.history/start-time inst?)
(s/def :rebecca.history/end-time inst?)

;;; (Generate a conforming history object)
(defn history [q] (merge {:messages q}
                         (if (seq q)
                           {:start-time (:timestamp (peek q))
                            :end-time (:timestamp (last q))})))

;;; (Verify consistency of :start-time and :end-time w.r.t. messages)
(defn verify-hist-end-start [h]
  (let [{:keys [messages start-time end-time]} h]
    (and (= start-time (:timestamp (peek messages)))
         (= end-time (:timestamp (last messages))))))

(s/def :rebecca/history (s/and
                         (s/keys :req-un [:rebecca.history/messages]
                                 :opt-un [:rebecca.history/start-time
                                          :rebecca.history/end-time]
                                 :gen #(gen/fmap
                                        history
                                        (s/gen :rebecca.history/messages)))
                         verify-hist-end-start))
