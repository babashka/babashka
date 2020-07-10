(ns client-test
  (:require [datomic.client.api :as d]))

(System/setProperty
  "java.home" 
  (System/getenv "GRAALVM_HOME"))

(def client
  (d/client
   {:server-type :peer-server
    :endpoint "localhost:8080"
    :secret "bar"
    :access-key "foo"
    :validate-hostnames false}))

(def conn 
  (d/connect client {:db-name "yellowdig"}))

(def db 
  (d/db conn))

(println
  (pr-str
    (ffirst
      (d/q
        '[:find (pull ?e [:user/firstname :user/lastname]) 
          :where [?e :user/username "bingo"]]
        db))))
