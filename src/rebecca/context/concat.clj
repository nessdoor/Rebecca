(ns rebecca.context.concat
  (:import java.time.DateTimeException
           java.time.temporal.ChronoUnit))

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
