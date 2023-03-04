(ns rebecca.core
  (:require [wkok.openai-clojure.api :as oai]))

(defn get-reply
  [context input]
  (oai/create-completion {:model "text-davinci-003"
                          :prompt (str context "\n[Other]:" input "\n[Me]:")
                          :max-tokens 30
                          :temperature 0}))
