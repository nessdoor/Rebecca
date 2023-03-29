(ns rebecca.context.history
  (:require [clojure.string :as cstr]
            [clojure.spec.alpha :as s]
            [clojure.math :refer [round]]
            [rebecca.context.spec])
  (:import (java.time DateTimeException Instant)
           java.time.temporal.ChronoUnit))

(def default-token-estimator (fn [seg-text]
                               (round
                                (* 4/3
                                   (count (cstr/split seg-text #"\s+"))))))

(def default-token-limit 2048)

(def default-trim-factor 3/4)

(defn component
  [text & {:keys [timestamp tokens speaker] :as opts
           :or {timestamp (Instant/now)
                tokens (default-token-estimator text)}}]
  {:post [(s/valid? :rebecca/component %)
          (s/valid? :rebecca.component/meta (meta %))]}
   (with-meta
     (merge {:text text :timestamp timestamp}
            (if speaker {:speaker speaker}))
     {:tokens tokens}))

(defn history
  [& {:keys [tokens-estimator tokens-limit trim-factor] :as opts}]
  {:post [(s/valid? :rebecca/history %)
          (empty? (:components %))
          (s/valid? :rebecca.history/meta (meta %))
          (= 0 (:tokens (meta %)))]}
   (with-meta
     {:components clojure.lang.PersistentQueue/EMPTY}
     (merge {:tokens 0} opts)))

(defn h-empty? [h] (empty? (:components h)))

(defn h-trim
  [h]
   {:pre [(s/valid? :rebecca/history h)
          (s/valid? :rebecca.history/meta (meta h))
          (contains? (meta h) :tokens-limit)]
    :post [(s/valid? :rebecca/history %)
           (s/valid? :rebecca.history/meta (meta %))
           (let [{:keys [tokens tokens-limit]} (meta %)]
             (<= tokens tokens-limit))]}
  (let [{:keys [tokens tokens-limit trim-factor]
         :or {trim-factor default-trim-factor}} (meta h)
        {:keys [components]} h]
    ;; Pop from history until we have recouped enough tokens
    (loop [cmps components
           tgoal (- tokens (* tokens-limit trim-factor))
           recouped 0]
      (if (< recouped tgoal)
        (let [ctok (:tokens (meta (peek cmps)))]
          (recur (pop cmps) tgoal (+ recouped ctok)))
        ;; Once the goal is reached, recreate history from the shortened queue
        (vary-meta
         (let [new-hist (assoc h :components cmps)
               new-start (:timestamp (peek cmps))]
           (if (nil? new-start)
             ;; History is now empty, delete all time references
             (dissoc new-hist :start-time :end-time)
             ;; Start time equal to the timestamp of the 1st component
             (assoc new-hist :start-time new-start)))
         merge {:tokens (- tokens recouped)})))))

(defn enq-keep-time
  [queue cs last]
  (if (seq cs)
    (let [fc (first cs)]
      (recur (conj queue fc) (next cs) (:timestamp fc)))
    [queue last]))

(defn h-conj-unchecked
  ([]
   {:post [(s/valid? :rebecca/history %)
           (s/valid? :rebecca.history/meta (meta %))
           (h-empty? %)]} (history))
  ([hist] {:post [(identical? hist %)]} hist)
  ([hist & cs]
   {:pre [(s/valid? :rebecca/history hist)
          (s/valid? :rebecca.history/meta (meta hist))
          (s/valid? (s/* :rebecca/component) cs)
          (s/valid? (s/* :rebecca.component/meta) (map meta cs))
          (let [end (:end-time hist)
                time (:timestamp (first cs))]
            (or (nil? end) (= end time) (.isBefore end time)))]
    :post [(s/valid? :rebecca/history %)
           (s/valid? :rebecca.history/meta (meta %))
           (= (:components %)
              (concat (:components hist) cs))
           (= (:tokens (meta %))
              (reduce + (:tokens (meta hist))
                      (map (fn [c] (:tokens (meta c))) cs)))]}
   (with-meta
     (merge hist
            (let [[ccs end] (enq-keep-time
                             (:components hist) cs (:end-time hist))
                  {start :timestamp} (peek ccs)]
              {:components ccs :start-time start :end-time end}))
     (let [mhist (meta hist)
           {t :tokens} mhist
           ts (reduce + (map #(:tokens (meta %)) cs))]
       (assoc mhist :tokens (+ t ts))))))

(defn h-conj
  ([]
   {:post [(s/valid? :rebecca/history %)
           (h-empty? %)]} (history))
  ([hist] {:post [(identical? hist %)]} hist)
  ([hist & cs]
   {:pre [(s/valid? :rebecca/history hist)
          (s/valid? :rebecca.history/meta (meta hist))
          (s/valid? (s/+ :rebecca/component) cs)
          (s/valid? (s/+ :rebecca.component/meta) (map meta cs))
          (let [end (:end-time hist)
                time (:timestamp (first cs))]
            (or (nil? end) (= end time) (.isBefore end time)))]
    :post [(s/valid? :rebecca/history %)
           (s/valid? :rebecca.history/meta (meta %))
           (let [{:keys [tokens tokens-limit]} (meta %)]
             (or (nil? tokens-limit)
                 (<= tokens tokens-limit)))]}
   (let [result (apply h-conj-unchecked hist cs)
         {htok :tokens htlim :tokens-limit} (meta result)]
     (if (and htlim (> htok htlim))
       (h-trim result)
       result))))

(defn h-concat-unchecked
  ([] nil)
  ([l] {:post [(identical? l %)]} l)
  ([l r]
   {:pre [(s/valid? :rebecca/history l)
          (s/valid? :rebecca.history/meta (meta l))
          (s/valid? :rebecca/history r)
          (s/valid? :rebecca.history/meta (meta r))
          (let [{lend :end-time} l
                {rbeg :start-time} r]
            (or (nil? lend) (nil? rbeg)
                (= lend rbeg)
                (.isBefore lend rbeg)))]
    :post [(s/valid? :rebecca/history %)
           (s/valid? :rebecca.history/meta (meta %))
           (= (:components %)
              (concat (:components l) (:components r)))
           (= (:tokens (meta %))
              (+ (:tokens (meta l))
                 (:tokens (meta r))))]}
   (with-meta
    ;; Extra keys of rightmost history take precedence
    (merge l r
           {:components (into (:components l) (:components r))
            ;; The time ranges of empty histories must be overridden
            :start-time (:start-time l (:start-time r))
            :end-time (:end-time r (:end-time l))})
    ;; Metadata is merged from right to left, and token count is summed
     (merge (meta r) (meta l)
            {:tokens (+ (:tokens (meta l)) (:tokens (meta r)))})))
  ([l r & rs]
   (reduce h-concat-unchecked (h-concat-unchecked l r) rs)))

(defn h-concat
  ([] nil)
  ([l] {:post [(identical? l %)]} l)
  ([l r]
   {:pre [(s/valid? :rebecca/history l)
          (s/valid? :rebecca.history/meta (meta l))
          (s/valid? :rebecca/history r)
          (s/valid? :rebecca.history/meta (meta r))
          (let [{lend :end-time} l
                {rbeg :start-time} r]
            (or (nil? lend) (nil? rbeg)
                (= lend rbeg)
                (.isBefore lend rbeg)))]
    :post [(s/valid? :rebecca/history %)
           (s/valid? :rebecca.history/meta (meta %))
           (let [{:keys [tokens tokens-limit]} (meta %)]
             (or (nil? tokens-limit)
                 (<= tokens tokens-limit)))]}
   (let [result (h-concat-unchecked l r) ; Unchecked history concatenation
         {ctok :tokens ctlim :tokens-limit} (meta result)]
     ;; If there is a limit on the total number of tokens and this has been
     ;; surpassed, trim history
     (if (and ctlim
              (> ctok ctlim))
       (h-trim result)
       result)))
  ([l r & rs]
   (let [result (apply h-concat-unchecked l r rs)
         {ctok :tokens ctlim :tokens-limit} (meta result)]
     (if (and ctlim
              (> ctok ctlim))
       (h-trim result)
       result))))
