(ns rebecca.history
  (:require [clojure.spec.alpha :as s]
            [rebecca.seq :refer [queue pop-drop]]
            [rebecca.history-spec])
  (:import (java.time DateTimeException Instant)
           java.time.temporal.ChronoUnit))

(defn message
  "Creates a message containing the given text. Optionally, a message
  timestamp and a string identifying the speaker (i.e. who sent the
  message) can be provided. By default, the message will carry a
  timestamp corresponding to the instant when it was created, and will
  specify no speaker."
  [text & {:keys [timestamp speaker] :as opts
           :or {timestamp (Instant/now)}}]
  {:post [(s/valid? :rebecca/message %)]}
  (merge {:text text :timestamp timestamp}
         (if speaker {:speaker speaker})))

(def EMPTY
  "The empty history, containing an empty message queue and no time
  range indication."
  {:messages (queue)})

(defn h-empty?
  "Returns true when the given history is empty."
  [h] (empty? (:messages h)))

(defn- enq-keep-time
  "Accumulates a sequence of messages onto a queue, keeping track of the
  timestamp of the newest message, so that a single scan is necessary
  to construct a new history. Internal use only."
  [queue cs last]
  (if (seq cs)
    (let [fc (first cs)]
      (recur (conj queue fc) (next cs) (:timestamp fc)))
    [queue last]))

(defn h-conj
  "With no arguments, returns an empty history. With hist only, returns
  the same hist unaltered. With hist and one or more messages (cs),
  returns a history with the given messages appended to its
  end. Messages must be chronologically ordered and simultaneous or
  newer than the newest message on the queue."
  ([] EMPTY)
  ([hist] {:post [(identical? hist %)]} hist)
  ([hist & cs]
   {:pre [(s/valid? :rebecca/history hist)
          (s/valid? (s/* :rebecca/message) cs)
          (let [end (:end-time hist)
                time (:timestamp (first cs))]
            (or (nil? end) (= end time) (.isBefore end time)))]
    :post [(s/valid? :rebecca/history %)
           (= (:messages %)
              (concat (:messages hist) cs))]}
   (merge hist
          (let [[ccs end] (enq-keep-time
                           (:messages hist) cs (:end-time hist))
                {start :timestamp} (peek ccs)]
            {:messages ccs :start-time start :end-time end}))))

(defn h-drop
  "Drops the oldest n messages. If hist is empty or shorter than n,
  returns an empty history."
  [n hist]
  (let [new-queue (pop-drop n (:messages hist))
        {new-start :timestamp} (peek new-queue)
        short-hist (assoc hist :messages new-queue)]
    ;; If history is empty, remove time range
    (if (h-empty? short-hist)
      (-> short-hist
          (dissoc :start-time)
          (dissoc :end-time))
      (assoc short-hist :start-time new-start))))

(defn h-concat
  "Concatenates two or more histories together, from left to
  right. Concatenated histories must be chronologically ordered.  With
  no arguments, returns nil. With one argument, returns the argument
  unchanged."
  ([] nil)
  ([l] {:post [(identical? l %)]} l)
  ([l r]
   {:pre [(s/valid? :rebecca/history l)
          (s/valid? :rebecca/history r)
          (let [{lend :end-time} l
                {rbeg :start-time} r]
            (or (nil? lend) (nil? rbeg)
                (= lend rbeg)
                (.isBefore lend rbeg)))]
    :post [(s/valid? :rebecca/history %)
           (= (:messages %)
              (concat (:messages l) (:messages r)))]}
   ;; Extra keys of rightmost history take precedence
   (merge l r
          {:messages (into (:messages l) (:messages r))}
          ;; If both histories are empty, the resulting history shall not have a
          ;; time range
          (if (or (:start-time l) (:start-time r))
            {:start-time (:start-time l (:start-time r))})
          (if (or (:end-time l) (:end-time r))
            {:end-time (:end-time r (:end-time l))})))
  ([l r & rs]
   (reduce h-concat (h-concat l r) rs)))
