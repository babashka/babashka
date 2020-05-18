(ns embedded-pg-tests
  (:require [clojure.test :as t :refer [is]]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]))

(defn wait-for-postgres
  [db]
  (while
      (not
       (try (jdbc/execute! db ["select version()"])
            (catch Exception e
              (prn (ex-message e))
              (Thread/sleep 100))))))

(defn create-table-test [db]
  (is (jdbc/execute! db ["drop table if exists foo; create table foo ( foo text )"])))

(defn insert-test [db]
  (is (sql/insert-multi! db :foo [:foo] [["foo"] ["bar"] ["baz"]])))

(defn query-test [db]
  (let [results (jdbc/execute! db ["select * from foo"])]
    results))
