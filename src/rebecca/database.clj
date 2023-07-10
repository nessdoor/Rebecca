(ns rebecca.database
  (:require [xtdb.api :as xt]))

(def worldinfo-db {:dbtype "h2" :dbname "world-info"})

(def db
  (xt/start-node {:xtdb.jdbc/connection-pool {:dialect {:xtdb/module 'xtdb.jdbc.h2/->dialect}
                                              :db-spec worldinfo-db}
                  :xtdb/tx-log {:xtdb/module 'xtdb.jdbc/->tx-log
                                :connection-pool :xtdb.jdbc/connection-pool}
                  :xtdb/document-store {:xtdb/module 'xtdb.jdbc/->document-store
                                        :connection-pool :xtdb.jdbc/connection-pool}}))
