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


(println "- transact \n")
(let [data [{:db/id "temp-foo" :test/name :foo}]
      tx-res (d/transact conn {:tx-data data})]
  (println (:tempids tx-res))
  (d/pull
    (:db-after tx-res)
    {:eid (get (:tempids tx-res) "temp-foo")
     :selector '[*]}))
(println "\n")

(println "- query \n")
(println
  (ffirst
    (d/q
      '[:find (pull ?e [:user/username :user/firstname]) 
        :where [?e :user/username "bingo"]]
      db)))
(println "\n")


(println "- tx-range \n")
(println
  (doseq [tx
          (d/tx-range
            conn
            {:limit 10
             :start nil
             :end nil})]
    (println (:id tx))))
(println "\n")

(println "- index range \n")
(println 
  (take 10
        (d/index-range
          (d/db conn)
          {:attrid :network/name})))
(println "\n")


