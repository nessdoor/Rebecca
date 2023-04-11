(ns rebecca.context-test
  (:require (rebecca [context :as sut]
                     [context-spec :as cs]
                     [history-spec :as hs])
            [clojure.spec.alpha :as s]
            [clojure.test :as t]
            [clojure.test.check :as tc]
            (clojure.test.check [generators :as gen]
                                [properties :as prop]
                                [clojure-test :as ct])))

