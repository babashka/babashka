(ns babashka.postgresql-test
  (:require [babashka.test-utils :as tu]
            [clojure.test :as t :refer [deftest is]])
  (:import [com.opentable.db.postgres.embedded EmbeddedPostgres]))

(def db {:dbtype "embedded-postgres"
         :port 54322})

(defmethod clojure.test/report :begin-test-var [m]
  (println "===" (-> m :var meta :name))
  (println))

(defn jdbc-feature-flag? []
  (= (System/getenv "BABASHKA_FEATURE_JDBC") "true"))

(defn pg-feature-flag? []
  (= (System/getenv "BABASHKA_FEATURE_POSTGRESQL") "true"))

(deftest postgresql-test
  (when (= "embedded-postgres" (:dbtype db))
    (if (and tu/jvm? (jdbc-feature-flag?) (pg-feature-flag?))
      (let [pg-db-instance (-> (EmbeddedPostgres/builder)
                               (.setPort (:port db))
                               .start
                               .getPostgresDatabase
                               .getConnection)
            bb-tests (tu/bb nil
                            "-e" "(require '[clojure.test :as t :refer [deftest is]])"
                            "-e" "(require '[next.jdbc :as jdbc])"
                            "-e" "(require '[next.jdbc.sql :as sql])"
                            "-e" "(load-file (io/file \"test-resources\" \"babashka\" \"embedded_pg_tests.clj\"))"
                            "-e" "(require '[embedded-pg-tests :as em-pg])"
                            "-e" "(def db {:dbtype \"postgres\" :port 54322 :user \"postgres\" :dbname \"postgres\"})"
                            "-e" "(def expected-query-res [{:foo/foo \"foo\"} {:foo/foo \"bar\"} {:foo/foo \"baz\"}])"
                            "-e" "(em-pg/wait-for-postgres db)"
                            "-e" "(em-pg/create-table-test db)"
                            "-e" "(em-pg/insert-test db)"
                            "-e" "(is (= expected-query-res (em-pg/query-test db)))")]
        (is (= bb-tests "true\n"))
        (.close pg-db-instance))
      (println "Did not run the pg-tests. Turn on the feature flags to run these tests."))))
