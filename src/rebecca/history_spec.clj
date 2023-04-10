(ns rebecca.history-spec
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen])
  (:import java.time.Instant))

;;; Message: a textual piece of information said by someone
(s/def :rebecca.message/speaker string?)
(s/def :rebecca.message/text string?)
(s/def :rebecca.message/timestamp (s/with-gen inst?
                                      #(gen/fmap
                                        (fn [s] (Instant/ofEpochSecond s))
                                        (gen/such-that
                                         (fn [s]
                                           (<= (.getEpochSecond (Instant/MIN))
                                               s
                                               (.getEpochSecond (Instant/MAX))))
                                         (s/gen int?)))))
(s/def :rebecca/message (s/keys :req-un [:rebecca.message/text
                                         :rebecca.message/timestamp]
                                :opt-un [:rebecca.message/speaker]))

;;; Verify whether the given object is a Clojure persistent queue
(def persistent-queue? #(instance? clojure.lang.PersistentQueue %))

;;; Persistent queue generator for property-based testing
(defn pqueue [generator] (gen/fmap #(into clojure.lang.PersistentQueue/EMPTY %)
                                   generator))

;;; Generate persistent queues of chronologically-ordered segments
(defn squeue-gen [] (pqueue (gen/fmap #(sort-by :timestamp %)
                                      (gen/list
                                       (s/gen :rebecca/message)))))

(defn chrono-ordered?
  ([] true)
  ([coll]
   (loop [prec (Instant/MIN), rst coll]
     (if (seq rst)
       (let [{succ :timestamp} (first rst)]
         (if (or (= prec succ)
                 (.isBefore prec succ))
           (recur succ (next rst))
           false))
       true))))

;;; History: a succession of messages
(s/def :rebecca.history/messages (s/with-gen
                                     (s/and
                                      (s/coll-of :rebecca/message
                                                 :kind persistent-queue?)
                                      #(chrono-ordered? %))
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
