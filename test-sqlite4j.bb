;; Compile with:
;; BABASHKA_FEATURE_JDBC=true BABASHKA_FEATURE_SQLITE4J=true script/uberjar && BABASHKA_FEATURE_JDBC=true BABASHKA_FEATURE_SQLITE4J=true script/compile

(require '[next.jdbc :as jdbc]
         '[next.jdbc.result-set :as rs])

(Class/forName "io.roastedroot.sqlite4j.JDBC")

(let [ds (jdbc/get-datasource "jdbc:sqlite::memory:")]
  (with-open [conn (jdbc/get-connection ds)]
    (jdbc/execute! conn ["create table persons (id integer primary key, name text, age integer)"])
    (jdbc/execute! conn ["insert into persons (name, age) values (?, ?)" "Alice" 30])
    (jdbc/execute! conn ["insert into persons (name, age) values (?, ?)" "Bob" 25])
    (jdbc/execute! conn ["insert into persons (name, age) values (?, ?)" "Charlie" 35])

    (println "All persons:")
    (doseq [row (jdbc/execute! conn ["select * from persons"]
                               {:builder-fn rs/as-unqualified-maps})]
      (println (format "  %d: %s (age %d)" (:id row) (:name row) (:age row))))

    (println "\nPersons over 28:")
    (doseq [row (jdbc/execute! conn ["select * from persons where age > ?" 28]
                               {:builder-fn rs/as-unqualified-maps})]
      (println (format "  %s (age %d)" (:name row) (:age row))))))
