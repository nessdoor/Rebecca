(ns rebecca.core
  (:require [wkok.openai-clojure.api :as oai]
            [rebecca.context :refer [default-agent default-speaker
                                     context +facts +input epsilon-extend]]))

(def default-parameters {:model "text-davinci-003"
                         :temperature 0})

(defn try-complete
  [text & {:as model-params}]
  (oai/create-completion
   (merge default-parameters model-params {:prompt text})))
