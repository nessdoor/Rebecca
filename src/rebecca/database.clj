(ns rebecca.database
  (:require [clojure.core.async :as async :refer [<! >! >!!]]
            [clojure.data :refer [diff]]
            [clojure.set :as s]
            [java-time.api :as jt]
            [xtdb.api :as xt]
            [rebecca.update-bus :as bus]))

(def worldinfo-db {:dbtype "h2" :dbname "world-info"})

(def db
  (xt/start-node {:xtdb.jdbc/connection-pool {:dialect {:xtdb/module 'xtdb.jdbc.h2/->dialect}
                                              :db-spec worldinfo-db}
                  :xtdb/tx-log {:xtdb/module 'xtdb.jdbc/->tx-log
                                :connection-pool :xtdb.jdbc/connection-pool}
                  :xtdb/document-store {:xtdb/module 'xtdb.jdbc/->document-store
                                        :connection-pool :xtdb.jdbc/connection-pool}}))

(defmacro txfn
  [node fname & body]
  (let [func-id (keyword (name fname))
        fnbody (cons 'fn body)
        prev (:xt/fn (xt/entity (xt/db (eval node)) func-id))]
    (when (not= prev fnbody)
      (list 'xt/submit-tx node
            [[::xt/put {:xt/id func-id :xt/fn (list 'quote fnbody)}]]))))

(defn put-with-functions
  ([doc fns] (put-with-functions doc fns nil))
  ([doc fns valid-time]
   (if (seq fns)
     [[::xt/fn (first fns) doc (rest fns) valid-time]]
     (if valid-time
       [[::xt/put doc (jt/java-date valid-time)]]
       [[::xt/put doc]]))))

(txfn db no-alter
  [ctxt doc other-fns valid-time]
  (let [prev (-> (xtdb.api/db ctxt)
                 (xtdb.api/entity (:xt/id doc)))
        overlap (-> (clojure.data/diff prev doc)
                    (get 2))]
    (let [out (cond
                ;; Document not present: continue.
                (nil? prev) (rebecca.database/put-with-functions
                             doc other-fns valid-time)
                ;; Document does not add new information: ignore.
                (= doc overlap) []
                ;; Trying to alter document: abort.
                :else false)]
      out)))

(txfn db maybe-put
  [ctxt doc other-fns valid-time]
  (let [overlap (-> (xtdb.api/db ctxt)
                    (xtdb.api/entity (:xt/id doc))
                    (clojure.data/diff doc)
                    (get 2))]
    ;; If the documents adds no new information, ignore the update
    (if (= doc overlap)
      []
      (rebecca.database/put-with-functions doc other-fns valid-time))))

(txfn db merge-update
  [ctxt doc other-fns valid-time]
  (let [e (xtdb.api/entity (xtdb.api/db ctxt) (:xt/id doc))]
    (rebecca.database/put-with-functions (merge e doc) other-fns valid-time)))

(def msg-put-constraints [:no-alter])

(def acct-put-constraints [:maybe-put :merge-update])

(def src-put-constraints [:maybe-put :merge-update])

(defn- to-xt-id [o]
  (s/rename-keys o {:id :xt/id}))

(defn- extract-timestamp [o]
  [o (:timestamp (meta o))])

(defn- remove-meta [o] (with-meta o {}))

(defmulti gen-put-op #(get-in % [0 :type]))

(defmethod gen-put-op :rebecca/message
  [[m v]]
  (put-with-functions m msg-put-constraints v))

(defmethod gen-put-op :rebecca/account
  [[a v]]
  (put-with-functions a acct-put-constraints v))

(defmethod gen-put-op :rebecca/datasource
  [[d v]]
  (put-with-functions d src-put-constraints v))

(def xf-to-transaction
  (comp
   (map to-xt-id)
   (map extract-timestamp)
   (map #(update % 0 remove-meta))
   (map gen-put-op)
   cat))

(def xf-prepare
  (map #(update % 1 (fn [us]
                      (into [] xf-to-transaction us)))))

(def live-upd-chan (async/chan 1 xf-prepare))

(bus/tap-update-stream live-upd-chan)

(def commission-signaler (agent {}))

(defn signal-tx-committed [state tx]
  (let [tx-id (::xt/tx-id tx)]
    (if-let [ack-chan (get state tx-id)]
      (do
        (>!! ack-chan (:committed? tx false))
        (dissoc state tx-id))
      state)))

(defn expect-tx-committed [state ack-chan tx]
  (try
    (>!! ack-chan (xt/tx-committed? db tx))
    state
    (catch xtdb.api.NodeOutOfSyncException _
      (assoc state (::xt/tx-id tx) ack-chan))))

(def tx-comm-listener
  (xt/listen db {::xt/event-type ::xt/indexed-tx}
             #(send commission-signaler signal-tx-committed %)))

(def transactor (async/go-loop []
                  (let [[ack-chan tx-ops] (<! live-upd-chan)
                        tx (xt/submit-tx db tx-ops)]
                    (when ack-chan
                      (send commission-signaler
                            expect-tx-committed ack-chan tx))
                    (recur))))
