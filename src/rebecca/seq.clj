(ns rebecca.seq)

(defn queue
  "Creates an empty persistent queue, or one with elements drawn from coll."
  ([] clojure.lang.PersistentQueue/EMPTY)
  ([coll] (into (queue) coll)))

(defn queue?
  "Returns true if q is a persistent queue."
  [q] (instance? clojure.lang.PersistentQueue q))

(defn pop-drop
  "Like clojure.core drop, but repeatedly calling pop on the
  collection. This means that this function has different behaviors
  depending on the concrete sequence type."
  [n coll]
  (if coll
    (if (< 0 n)
      (recur (dec n) (pop coll))
      coll)
    (empty coll)))
