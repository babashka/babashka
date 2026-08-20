;; babashka.duckdb (examples/babashka/duckdb.clj) demo.
;; Run: bb -cp examples ffi-duckdb.clj

(require '[babashka.duckdb :as duck]
         '[babashka.fs :as fs]
         '[clojure.string :as str])

(println "duckdb" (duck/version))

;; duckdb's party trick: SQL directly over a file
(def csv (str (fs/path (fs/temp-dir) "bb-duck-orders.csv")))
(spit csv (str/join "\n"
                    ["order_id,customer,amount,day"
                     "1,ada,120.50,2026-08-14"
                     "2,grace,80.00,2026-08-14"
                     "3,ada,42.25,2026-08-15"
                     "4,linus,310.10,2026-08-16"
                     "5,grace,55.75,2026-08-16"
                     "6,ada,99.99,2026-08-17"]))

(println "per customer:"
         (duck/query nil [(str "select customer, count(*) n, sum(amount) total "
                               "from read_csv_auto(?) group by customer order by total desc")
                          csv]))

;; in-memory analytics with a held connection
(duck/with-db [db nil]
  (duck/execute! db [(str "create table orders as select * from read_csv_auto('" csv "')")])
  (println "daily:"
           (duck/query db "select day, sum(amount) total from orders group by day order by day"))
  (println "window fn:"
           (duck/query db (str "select customer, amount, "
                               "rank() over (order by amount desc) r "
                               "from orders limit 3")))
  (println "params:"
           (duck/query db ["select * from orders where amount > ? and customer = ?"
                           50 "ada"])))

(fs/delete-if-exists csv)
(println "DUCKDB OK")
