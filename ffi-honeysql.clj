;; babashka.sqlite (examples/babashka/sqlite.clj) driven by honeysql.
;; Run: ./bb -cp examples ffi-honeysql.clj

(require '[babashka.deps :as deps])
(deps/add-deps '{:deps {com.github.seancorfield/honeysql {:mvn/version "2.6.1270"}}})

(require '[babashka.sqlite :as sqlite]
         '[honey.sql :as sql]
         '[babashka.fs :as fs])

(def db-path (str (fs/path (fs/temp-dir) "bb-ffi-honeysql.db")))
(fs/delete-if-exists db-path)

;; pod-babashka-go-sqlite3 style: path per call
(sqlite/execute! db-path
  ["create table if not exists foo (the_text TEXT, the_int INTEGER, the_real REAL, the_blob BLOB)"])

(println "insert:"
         (sqlite/execute! db-path
           (sql/format {:insert-into :foo
                        :values [{:the_text "hello" :the_int 1 :the_real 3.14
                                  :the_blob (byte-array [1 2 3])}
                                 {:the_text "world" :the_int 2 :the_real 2.71
                                  :the_blob nil}]})))

(println "query:"
         (sqlite/query db-path
           (sql/format {:select [:the_text :the_int :the_real]
                        :from :foo
                        :where [:>= :the_int 1]
                        :order-by [[:the_int :asc]]})))

;; blob round trip
(println "blob:"
         (vec (:the_blob (first (sqlite/query db-path
                                  ["select the_blob from foo where the_int = ?" 1])))))

;; held connection + with-db
(sqlite/with-db [db db-path]
  (dotimes [i 100]
    (sqlite/execute! db ["insert into foo (the_text, the_int) values (?, ?)"
                         (str "row" i) (+ 10 i)]))
  (println "count:" (sqlite/query db "select count(*) c from foo"))
  (println "nil/param/bool:"
           (sqlite/query db ["select ? a, ? b, ? c" nil 42 true])))

(fs/delete-if-exists db-path)
(println "SQLITE-HONEYSQL OK")
