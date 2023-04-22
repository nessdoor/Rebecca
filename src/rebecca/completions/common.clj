(ns rebecca.completions.common
  (:require [clojure.string :as cstr]
            [clojure.spec.alpha :as s]
            [java-time.api :as jt]
            [rebecca.history :as h]))

(def default-token-estimator (fn [seg-text]
                               (* 3 (count (cstr/split seg-text #"\s+")))))

(def default-token-limit 2048)

(def default-trim-factor 3/4)

(defn- trimming-plan
  [tokens limit trim-factor]
  (let [total (reduce + tokens)
        goal (- total (* limit trim-factor))]
    (loop [recouped 0, leap 0, residue tokens]
      (if (< recouped goal)
        (recur (+ recouped (first residue))
               (inc leap)
               (rest residue))
        [leap (- total recouped) residue]))))

(defn trim-context
  ([ctxt tokens limit]
   (trim-context ctxt tokens limit default-trim-factor))
  ([ctxt tokens limit trim-factor]
   (let [[leap final-size _] (trimming-plan tokens limit trim-factor)]
     (vary-meta
      (h/h-drop leap ctxt)
      assoc :tokens final-size))))

(defn msg-header
  ([speaker] (msg-header speaker (jt/instant)))
  ([speaker instant]
   (format "[%s|%s]:" speaker (jt/truncate-to instant :seconds))))

(defn h-trim
  [h]
  {:pre [(s/valid? :rebecca/history h)
         (contains? (meta h) :tokens)
         (contains? (meta h) :tokens-limit)]
   :post [(s/valid? :rebecca/history %)
          (= (dissoc (meta h) :tokens)
             (dissoc (meta %) :tokens))
          (let [{:keys [tokens tokens-limit]} (meta %)]
            (<= tokens tokens-limit))]}
  (let [{:keys [tokens tokens-limit trim-factor]
         :or {trim-factor default-trim-factor}} (meta h)
        {:keys [messages]} h]
    ;; Pop from history until we have recouped enough tokens
    (loop [cmps messages
           tgoal (- tokens (* tokens-limit trim-factor))
           recouped 0]
      (if (< recouped tgoal)
        (let [ctok (:tokens (meta (peek cmps)))]
          (recur (pop cmps) tgoal (+ recouped ctok)))
        ;; Once the goal is reached, recreate history from the shortened queue
        (vary-meta
         (let [new-hist (assoc h :messages cmps)
               new-start (:timestamp (peek cmps))]
           (if (nil? new-start)
             ;; History is now empty, delete all time references
             (dissoc new-hist :start-time :end-time)
             ;; Start time equal to the timestamp of the 1st message
             (assoc new-hist :start-time new-start)))
         merge {:tokens (- tokens recouped)})))))
