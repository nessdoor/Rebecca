(ns rebecca.context.spec
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen])
  (:import java.time.Instant))

;;; Token count: number of tokens of which a certain text is composed of
(s/def :rebecca/tokens number?)

;;; Expansion: textual expansion of a segment
(s/def :rebecca.history/expansion string?)

;;; Component: a textual piece of information said by someone
(s/def :rebecca.history/speaker string?)
(s/def :rebecca.history/text string?)
(s/def :rebecca.history/timestamp inst?)
(s/def :rebecca.history/component-meta (s/keys :req-un [:rebecca/tokens]
                                               :opt-un [:rebecca.history/expansion]))
(s/def :rebecca.history/component (s/keys :req-un [:rebecca.history/text
                                                   :rebecca.history/timestamp]
                                          :opt-un [:rebecca.history/speaker]))

;;; Verify whether the given object is a Clojure persistent queue
(def persistent-queue? #(instance? clojure.lang.PersistentQueue %))

;;; Persistent queue generator for property-based testing
(defn pqueue [generator] (gen/fmap #(into clojure.lang.PersistentQueue/EMPTY %)
                                   generator))

;;; Generate persistent queues of chronologically-ordered segments
(defn squeue-gen [] (pqueue (gen/fmap #(sort-by :timestamp %)
                                      (gen/list
                                       (s/gen :rebecca.history/component)))))

;;; History: a succession of components
(s/def :rebecca.history/components (s/with-gen
                                     (s/coll-of :rebecca.history/component
                                                :kind persistent-queue?)
                                     squeue-gen))
(s/def :rebecca.history/start-time inst?)
(s/def :rebecca.history/end-time inst?)
(s/def :rebecca.history/tokens-limit number?)
(s/def :rebecca.history/trim-factor number?)
(s/def :rebecca.history/tokens-estimator (s/fspec :args (s/cat :t string?)
                                                  :ret number?))
(s/def :rebecca.history/meta (s/keys :req-un [:rebecca/tokens]
                                     :opt-un [:rebecca.history/tokens-limit
                                              :rebecca.history/trim-factor
                                              :rebecca.history/tokens-estimator]))
(defn history [q] (merge
                   {:components q}
                   (if (empty? q)
                     {:start-time (Instant/MIN) :end-time (Instant/MIN)}
                     {:start-time (:timestamp (peek q))
                      :end-time (:timestamp (last q))})))
(s/def :rebecca/history (s/keys :req-un [:rebecca.history/components
                                         :rebecca.history/start-time
                                         :rebecca.history/end-time]
                                :gen #(gen/fmap
                                       history
                                       (s/gen :rebecca.history/components))))

;;; Context: a (potentially empty) history accompanied by auxiliary information
(s/def :rebecca.context/agent string?)
(s/def :rebecca.context/preamble string?)
(s/def :rebecca.context/pre-toks number?)
(s/def :rebecca.context/meta (s/keys :req-un [:rebecca/tokens
                                              :rebecca.context/pre-toks]))
(s/def :rebecca/context (s/keys :req-un [:rebecca.context/agent
                                         :rebecca.context/preamble]
                                :opt-un [:rebecca.history/components
                                         :rebecca.history/start-time
                                         :rebecca.history/end-time]))
