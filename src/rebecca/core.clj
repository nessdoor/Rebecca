(ns rebecca.core
  (:require [wkok.openai-clojure.api :as oai]))

(def default-parameters {:model "text-davinci-003"
                         :temperature 0})

(defn epsilon-extend
  [history & {:as model-params}]
  (oai/create-completion (assoc default-parameters :prompt (str history "\n[Me]:"))))

(defn get-reply
  [context input & {:keys [speaker] :or {speaker "Other"} :as extra}] ; Default speaker name is "Other"
  (epsilon-extend
   (format "%s\n[%s]:%s" context speaker input)
   (dissoc extra :speaker)))         ; Pass extra parameters to API w/o :speaker
