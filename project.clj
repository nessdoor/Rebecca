(defproject rebecca "0.1.0-SNAPSHOT"
  :description "An augmented neural conversational agent"
  :url "http://example.com/FIXME"
  :license {:name "Mozilla Public License Version 2.0"
            :url "http://mozilla.org/MPL/2.0/"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [org.clojure/core.async "1.6.673"]
                 [net.clojars.wkok/openai-clojure "0.5.0"]
                 [telegrambot-lib "2.5.0"]
                 [cheshire "5.11.0"]]
  :profiles {:dev {:dependencies [[org.clojure/test.check "1.1.1"]]}}
  :repl-options {:init-ns rebecca.core})
