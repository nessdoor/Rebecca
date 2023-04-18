(ns rebecca.history
  (:require [clojure.spec.alpha :as s]
            [rebecca.seq :refer [queue]]
            [rebecca.history-spec])
  (:import (java.time DateTimeException Instant)
           java.time.temporal.ChronoUnit))

(defn message
  [text & {:keys [timestamp speaker] :as opts
           :or {timestamp (Instant/now)}}]
  {:post [(s/valid? :rebecca/message %)]}
  (merge {:text text :timestamp timestamp}
         (if speaker {:speaker speaker})))

(def EMPTY {:messages (queue)})

(defn h-empty? [h] (empty? (:messages h)))

(defn enq-keep-time
  [queue cs last]
  (if (seq cs)
    (let [fc (first cs)]
      (recur (conj queue fc) (next cs) (:timestamp fc)))
    [queue last]))

(defn h-conj
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

(defn h-concat
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
