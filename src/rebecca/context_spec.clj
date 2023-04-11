(ns rebecca.context-spec
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [rebecca.history-spec])
  (:import java.time.Instant))

;;; Context: a (potentially empty) history accompanied by auxiliary information
(s/def :rebecca.context/agent string?)
(s/def :rebecca.context/preamble string?)
(s/def :rebecca/context (s/merge :rebecca/history
                                 (s/keys :req-un [:rebecca.context/agent
                                                  :rebecca.context/preamble])))
