(ns rebecca.core
  (:require [wkok.openai-clojure.api :as oai]
            [clojure.string :as cstr]))

(defn get-reply
  [context input]
  (oai/create-completion {:model "text-davinci-003"
                          :prompt (cstr/join "\n[Other]: " (list context input))
                          :max-tokens 30
                          :temperature 0}))
