(ns babashka.postgres-test
  (:require [clojure.test :as t :refer [deftest is]]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]))

(def db {:dbtype "postgres"
         :port 54322
         :dbname "postgres"
         :user "postgres"
         :password "mysecretpassword"})

(defmethod clojure.test/report :begin-test-var [m]
  (println "===" (-> m :var meta :name))
  (println))

(defn wait-for-postgres []
  (while
      (not
       (try (jdbc/execute! db ["select version()"])
            (catch Exception _
              (Thread/sleep 100))))))

(deftest create-table-test
  (is (jdbc/execute! db ["drop table if exists foo; create table foo ( foo text )"])))

(deftest insert-test
  (is (sql/insert-multi! db :foo [:foo] [["foo"] ["bar"] ["baz"]])))

(deftest query-test
  (let [results (jdbc/execute! db ["select * from foo"])]
    (is (= '[{:foo/foo "foo"} {:foo/foo "bar"} {:foo/foo "baz"}]
           results))))

(defn test-ns-hook []
  (wait-for-postgres)
  (create-table-test)
  (insert-test)
  (query-test))

(let [{:keys [:fail :error]}
      (t/run-tests)]
  (System/exit (+ fail error)))
