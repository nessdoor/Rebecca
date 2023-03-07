(ns rebecca.core
  (:require [wkok.openai-clojure.api :as oai]))

(def default-parameters {:model "text-davinci-003"
                         :temperature 0})

(def default-speaker-name "Other")

(def default-agent-name "Me")

(defn epsilon-extend
  [history & {:as model-params}]
  (oai/create-completion
   (assoc default-parameters
          :prompt (str history
                       (format "\n[%s]:" default-agent-name)))))

(defn get-reply
  [context input & {:keys [speaker] :or {speaker default-speaker-name} :as extra}]
  (epsilon-extend
   (format "%s\n[%s]:%s" context speaker input)
   (dissoc extra :speaker)))         ; Pass extra parameters to API w/o :speaker
