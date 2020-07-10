(ns babashka.impl.datomic-client
  (:require [datomic.client.api :as d]
            ;; these libs and others are dynamically 
            ;; required by the client, depending on use
            [datomic.client.api.sync]
            [datomic.client.impl.pro]))

(def client-vars
  '#{administer-system
     as-of
     client
     connect
     create-database
     datoms
     db
     db-stats
     delete-database
     history
     index-pull
     index-range
     list-databases
     pull
     q
     qseq
     since
     sync
     transact
     tx-range
     with
     with-db})

(def client-namespace
  (->> client-vars
       (map (fn [v] [v (var-get (ns-resolve 'datomic.client.api v))]))
       (into {})))


