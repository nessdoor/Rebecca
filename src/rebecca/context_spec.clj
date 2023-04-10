(ns rebecca.context-spec
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [rebecca.history-spec])
  (:import java.time.Instant))

;;; Token count: number of tokens of which a certain text is composed of
(s/def :rebecca/tokens int?)

;;; Expansion: textual expansion of a message
(s/def :rebecca.message/expansion string?)

;;; Message metadata expected or produced by context manipulation code
(s/def :rebecca.message/meta (s/keys :req-un [:rebecca/tokens]
                                     :opt-un [:rebecca.message/expansion]))

;;; Token trimming parameters of contexts
(s/def :rebecca.history/tokens-limit pos-int?)
(s/def :rebecca.history/trim-factor (s/and number?
                                           #(< 0 % 1)))
(s/def :rebecca.history/tokens-estimator (s/fspec :args (s/cat :t string?)
                                                  :ret pos-int?))

;;; History metadata expected or produced by context manipulation code
(s/def :rebecca.history/meta (s/keys :req-un [:rebecca/tokens]
                                     :opt-un [:rebecca.history/tokens-limit
                                              :rebecca.history/trim-factor
                                              :rebecca.history/tokens-estimator]))

;;; Context: a (potentially empty) history accompanied by auxiliary information
(s/def :rebecca.context/agent string?)
(s/def :rebecca.context/preamble string?)
(s/def :rebecca.context/pre-toks pos-int?)
(s/def :rebecca.context/meta (s/merge :rebecca.history/meta
                                      (s/keys :req-un
                                              [:rebecca.context/pre-toks])))
(s/def :rebecca/context (s/merge :rebecca/history
                                 (s/keys :req-un [:rebecca.context/agent
                                                  :rebecca.context/preamble])))
