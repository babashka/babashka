(ns babashka.impl.datomic-client
  (:require [datomic.client.api :as d]
            ;; these libs and others
            ;; are dynamically required by the
            ;; client depending on use
            [datomic.client.api.sync]
            [datomic.client.impl.pro]))

(def client-namespace
 {'client d/client 
  'connect d/connect
  'db d/db
  'q d/q})

