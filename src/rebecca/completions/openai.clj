(ns rebecca.completions.openai
  (:require [clojure.string :as cstr]
            [wkok.openai-clojure.api :as oai]
            [rebecca.context.ops :refer [make-prompt]])
  (:import java.time.Instant))

(def default-parameters {:model "text-davinci-003"
                         :temperature 0})

(defn davinci-3-complete
  [ctxt & {:as model-params}]
  (let [{agent :agent pre :preamble comp :components} ctxt
        ctime (Instant/now)
        footer (make-prompt agent ctime) ; Chat prompt that stimulates response
        result (oai/create-completion
                (merge
                 default-parameters model-params
                 ;; Prompt is the concatenation of primer, history and footer
                 {:prompt (cstr/join
                           (concat (list pre)
                                   ;; Use the cached expansions to build prompt
                                   (map (fn [c] (:expansion (meta c))) comp)
                                   (list footer)))}))
        ;; Concatenate footer with the first completion to obtain final text
        completion (str footer (:text (first (:choices result))))]
    (with-meta
      {:speaker agent :text (subs completion (count footer)) :timestamp ctime}
      ;; Adjust toen usage estimation error with the response from the API
      {:tokens (- (:total_tokens (:usage result))
                  (:tokens (meta ctxt)))
       :expansion completion})))
