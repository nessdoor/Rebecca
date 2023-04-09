(ns rebecca.context-spec
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [rebecca.history-spec])
  (:import java.time.Instant))

;;; Context: a (potentially empty) history accompanied by auxiliary information
(s/def :rebecca.context/agent string?)
(s/def :rebecca.context/preamble string?)
(s/def :rebecca.context/pre-toks pos-int?)
(s/def :rebecca.context/meta (s/keys :req-un [:rebecca/tokens
                                              :rebecca.context/pre-toks]))
(s/def :rebecca/context (s/keys :req-un [:rebecca.context/agent
                                         :rebecca.context/preamble]
                                :opt-un [:rebecca.history/components
                                         :rebecca.history/start-time
                                         :rebecca.history/end-time]))
