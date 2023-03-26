(ns rebecca.context.history
  (:require [clojure.string :as cstr]
            [clojure.spec.alpha :as s]
            [rebecca.context.spec :refer [verify-hist-end-start]])
  (:import (java.time DateTimeException Instant)
           java.time.temporal.ChronoUnit))

(def default-token-estimator (fn [seg-text]
                               (* 4/3
                                  (count (cstr/split seg-text #"\s+")))))

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
          (verify-hist-end-start %)
          (s/valid? :rebecca.history/meta (meta %))
          (= 0 (:tokens (meta %)))]}
   (with-meta
     {:components clojure.lang.PersistentQueue/EMPTY
      :start-time (Instant/MIN) :end-time (Instant/MIN)}
     (merge {:tokens 0} opts)))

(defn h-conj
  ([] (history))
  ([hist] hist)
  ([hist component]
   {:pre [(s/valid? :rebecca/history hist)
          (s/valid? :rebecca.history/meta (meta hist))
          (s/valid? :rebecca/component component)
          (s/valid? :rebecca.component/meta (meta component))]
    :post [(s/valid? :rebecca/history %)
           (= 1 (count (:components %)))
           (= component (peek (:components %)))
           (verify-hist-end-start %)
           (s/valid? :rebecca.history/meta (meta %))
           (= (:tokens (meta component)) (:tokens (meta %)))]}
   (with-meta
     (merge hist
            (let [cs (conj (:components hist) component)
                  {start :timestamp} (peek cs)
                  {end :timestamp} component]
              {:components cs :start-time start :end-time end}))
     (let [mhist (meta hist)
           {ts :tokens} mhist
           {t :tokens} (meta component)]
       (assoc mhist :tokens (+ ts t)))))
  ([hist c & cs]
   (reduce h-conj (h-conj hist c) cs)))

(defn trim-history
  [h]
  (let [{:keys [tokens tokens-limit trim-factor] :or {trim-factor 1}} (meta h)
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
         (merge h {:components cmps
                   ;; Start time equal to the timestamp of the 1st component
                   :start-time (:timestamp (peek cmps))})
         merge {:tokens (- tokens recouped)})))))

(defn ccat-uncapped
  [l r]
  (vary-meta
   ;; Create a new history concatenating the left and right segments
   (merge l
          ;; Concatenate queues
          {:components
           (into (:components l clojure.lang.PersistentQueue/EMPTY)
                 (:components r))}
          ;; Merge time ranges, respecting timeless segments
          (if (or (contains? l :start-time)
                  (contains? r :start-time))
            {:start-time (:start-time l (:start-time r))})
          (if (or (contains? r :end-time)
                  (contains? l :end-time))
            {:end-time (:end-time r (:end-time l))}))
   ;; Metadata is merged, and token count is summed
   merge (meta r) {:tokens (+ (:tokens (meta l))
                              (:tokens (meta r)))}))

(defn ccat
  [l r]
  (let [result (ccat-uncapped l r) ; Unchecked history concatenation
        {ctok :tokens ctlim :tokens-limit} (meta result)]
    ;; If there is a limit on the total number of tokens and this has been
    ;; surpassed, trim history
    (if (and ctlim
             (> ctok ctlim))
      (trim-history result)
      result)))
