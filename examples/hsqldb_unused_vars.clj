#!/usr/bin/env bb

;; $ examples/hsqldb_unused_vars.clj src

;; |                   :VARS/NS |               :VARS/NAME |                     :VARS/FILENAME | :VARS/ROW | :VARS/COL |
;; |----------------------------+--------------------------+------------------------------------+-----------+-----------|
;; | babashka.impl.bencode.core |           read-netstring | src/babashka/impl/bencode/core.clj |       162 |         1 |
;; | babashka.impl.bencode.core |          write-netstring | src/babashka/impl/bencode/core.clj |       201 |         1 |
;; |      babashka.impl.classes | generate-reflection-file |      src/babashka/impl/classes.clj |       230 |         1 |
;; |    babashka.impl.classpath |      ->DirectoryResolver |    src/babashka/impl/classpath.clj |        12 |         1 |
;; |    babashka.impl.classpath |        ->JarFileResolver |    src/babashka/impl/classpath.clj |        37 |         1 |
;; |    babashka.impl.classpath |                 ->Loader |    src/babashka/impl/classpath.clj |        47 |         1 |
;; | babashka.impl.clojure.test |            file-position | src/babashka/impl/clojure/test.clj |       286 |         1 |
;; | babashka.impl.nrepl-server |             stop-server! | src/babashka/impl/nrepl_server.clj |       179 |         1 |
;; |              babashka.main |                    -main |              src/babashka/main.clj |       485 |         1 |

(ns hsqldb-unused-vars
  (:require
   [clojure.edn :as edn]
   [clojure.java.shell :refer [sh]]
   [clojure.pprint :refer [print-table]]
   [next.jdbc :as jdbc]
   [next.jdbc.sql :as sql]))

(def db "jdbc:hsqldb:mem:testdb;sql.syntax_mys=true")

(defn query [q]
  (jdbc/execute! db [q]))

(defn create-db! []
  (query "create table vars (
            ns text
            , name text
            , filename text
            , row int
            , col int )")
  (query "create table var_usages (
            \"from\" text
            , \"to\" text
            , name text
            , filename text
            , row int
            , col int )"))

(defn parse-int [^String x]
  (when x
    (Integer. x)))

(defn insert-vars! [var-definitions]
  (sql/insert-multi! db :vars [:ns :name :filename :row :col]
                     (map (juxt (comp str :ns)
                                (comp str :name)
                                :filename
                                (comp parse-int :row)
                                (comp parse-int :col))
                          var-definitions)))

(defn insert-var-usages! [var-usages]
  (sql/insert-multi! db :var_usages ["\"from\"" "\"to\"" :name :filename :row :col]
                     (map (juxt (comp str :from)
                                (comp str :to)
                                (comp str :name)
                                :filename
                                (comp parse-int :row)
                                (comp parse-int :col))
                          var-usages)))

(defn analysis->db [paths]
  (let [out (:out (apply sh "clj-kondo"
                         "--config" "{:output {:analysis true :format :edn}}"
                         "--lint" paths))
        analysis (:analysis (edn/read-string out))
        {:keys [:var-definitions :var-usages]} analysis]
    (insert-vars! var-definitions)
    (insert-var-usages! var-usages)))

(create-db!)
(analysis->db *command-line-args*)

(def unused-vars "
select distinct * from vars v
where (v.ns, v.name) not in
(select vu.\"to\", vu.name from var_usages vu)")

(print-table (query unused-vars))
