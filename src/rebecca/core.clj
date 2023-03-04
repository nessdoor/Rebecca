(ns rebecca.core
  (:require [wkok.openai-clojure.api :as oai]))

(def default-parameters {:model "text-davinci-003"
                         :temperature 0})

(defn get-reply
  [context input & {:keys [speaker] :or {speaker "Other"} :as extra}]  ; Default speaker name is "Other"
  (oai/create-completion (merge default-parameters
                                (dissoc extra :speaker)                ; Pass extra parameters to API w/o :speaker
                                {:prompt (format "%s\n[%s]:%s\n[Me]:" context speaker input)})))
