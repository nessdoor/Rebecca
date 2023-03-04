(ns rebecca.core
  (:require [wkok.openai-clojure.api :as oai]))

(defn get-reply
  ([context input] (get-reply context "Other" input)) ; Default speaker name is "Other"
  ([context speaker input]
  (oai/create-completion {:model "text-davinci-003"
                          :prompt (format "%s\n[%s]:%s\n[Me]:" context speaker input)
                          :max-tokens 30
                          :temperature 0})))
