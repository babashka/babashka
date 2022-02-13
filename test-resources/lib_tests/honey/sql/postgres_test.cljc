;; copied from https://github.com/nilenso/honeysql-postgres
;; on 2021-02-13 to verify the completeness of support for
;; those features within HoneySQL 2.x

;; where there are differences, the original code is kept
;; with #_ and the modified code follows it (aside from
;; the ns form which has numerous changes to both match
;; the structure of HoneySQL 2.x and to work with cljs)

(ns honey.sql.postgres-test
  (:refer-clojure :exclude [update partition-by set])
  (:require [clojure.test :refer [deftest is testing]]
            ;; pull in all the PostgreSQL helpers that the nilenso
            ;; library provided (as well as the regular HoneySQL ones):
            [honey.sql.helpers :as sqlh :refer
             [upsert on-conflict do-nothing on-constraint
              returning do-update-set
              ;; not needed because do-update-set can do this directly
              #_do-update-set!
              alter-table rename-column drop-column
              add-column partition-by
              ;; not needed because insert-into can do this directly
              #_insert-into-as
              create-table rename-table drop-table
              window create-view over with-columns
              create-extension drop-extension
              select-distinct-on
              ;; already part of HoneySQL
              insert-into values where select
              from order-by update set]]
            [honey.sql :as sql]))

(deftest upsert-test
  (testing "upsert sql generation for postgresql"
    (is (= ["INSERT INTO distributors (did, dname) VALUES (?, ?), (?, ?) ON CONFLICT (did) DO UPDATE SET dname = EXCLUDED.dname RETURNING *" 5 "Gizmo Transglobal" 6 "Associated Computing, Inc"]
           ;; preferred in honeysql:
           (-> (insert-into :distributors)
               (values [{:did 5 :dname "Gizmo Transglobal"}
                        {:did 6 :dname "Associated Computing, Inc"}])
               (on-conflict :did)
               (do-update-set :dname)
               (returning :*)
               sql/format)))
    (is (= ["INSERT INTO distributors (did, dname) VALUES (?, ?), (?, ?) ON CONFLICT (did) DO UPDATE SET dname = EXCLUDED.dname RETURNING *" 5 "Gizmo Transglobal" 6 "Associated Computing, Inc"]
           ;; identical to nilenso version:
           (-> (insert-into :distributors)
               (values [{:did 5 :dname "Gizmo Transglobal"}
                        {:did 6 :dname "Associated Computing, Inc"}])
               (upsert (-> (on-conflict :did)
                           (do-update-set :dname)))
               (returning :*)
               sql/format)))
    (is (= ["INSERT INTO distributors (did, dname) VALUES (?, ?) ON CONFLICT (did) DO NOTHING" 7 "Redline GmbH"]
           ;; preferred in honeysql:
           (-> (insert-into :distributors)
               (values [{:did 7 :dname "Redline GmbH"}])
               (on-conflict :did)
               do-nothing
               sql/format)))
    (is (= ["INSERT INTO distributors (did, dname) VALUES (?, ?) ON CONFLICT (did) DO NOTHING" 7 "Redline GmbH"]
           ;; identical to nilenso version:
           (-> (insert-into :distributors)
               (values [{:did 7 :dname "Redline GmbH"}])
               (upsert (-> (on-conflict :did)
                           do-nothing))
               sql/format)))
    (is (= ["INSERT INTO distributors (did, dname) VALUES (?, ?) ON CONFLICT ON CONSTRAINT distributors_pkey DO NOTHING" 9 "Antwerp Design"]
           ;; preferred in honeysql:
           (-> (insert-into :distributors)
               (values [{:did 9 :dname "Antwerp Design"}])
               (on-conflict (on-constraint :distributors_pkey))
               do-nothing
               sql/format)))
    (is (= ["INSERT INTO distributors (did, dname) VALUES (?, ?) ON CONFLICT (did) ON CONSTRAINT distributors_pkey DO NOTHING" 9 "Antwerp Design"]
           ;; with both name and clause:
           (-> (insert-into :distributors)
               (values [{:did 9 :dname "Antwerp Design"}])
               (on-conflict :did (on-constraint :distributors_pkey))
               do-nothing
               sql/format)))
    (is (= ["INSERT INTO distributors (did, dname) VALUES (?, ?) ON CONFLICT (did, dname) ON CONSTRAINT distributors_pkey DO NOTHING" 9 "Antwerp Design"]
           ;; with multiple names and a clause:
           (-> (insert-into :distributors)
               (values [{:did 9 :dname "Antwerp Design"}])
               (on-conflict :did :dname (on-constraint :distributors_pkey))
               do-nothing
               sql/format)))
    (is (= ["INSERT INTO distributors (did, dname) VALUES (?, ?) ON CONFLICT ON CONSTRAINT distributors_pkey DO NOTHING" 9 "Antwerp Design"]
           ;; almost identical to nilenso version:
           (-> (insert-into :distributors)
               (values [{:did 9 :dname "Antwerp Design"}])
               ;; in nilenso, this was (on-conflict-constraint :distributors_pkey)
               (upsert (-> (on-conflict (on-constraint :distributors_pkey))
                           do-nothing))
               sql/format)))
    (is (= ["INSERT INTO distributors (did, dname) VALUES (?, ?), (?, ?) ON CONFLICT (did) DO UPDATE SET dname = EXCLUDED.dname" 10 "Pinp Design" 11 "Foo Bar Works"]
           (sql/format {:insert-into :distributors
                        :values [{:did 10 :dname "Pinp Design"}
                                 {:did 11 :dname "Foo Bar Works"}]
                        ;; in nilenso, these two were a submap under :upsert
                        :on-conflict :did
                        :do-update-set :dname})))
    (is (= ["INSERT INTO distributors (did, dname) VALUES (?, ?) ON CONFLICT (did) DO UPDATE SET dname = EXCLUDED.dname || ? || d.dname || ?" 23 "Foo Distributors" " (formerly " ")"]
           (-> (insert-into :distributors)
               (values [{:did 23 :dname "Foo Distributors"}])
               (on-conflict :did)
               ;; nilenso:
               #_(do-update-set! [:dname "EXCLUDED.dname || ' (formerly ' || d.dname || ')'"])
               ;; honeysql
               (do-update-set {:dname [:|| :EXCLUDED.dname " (formerly " :d.dname ")"]})
               sql/format)))
    (is (= ["INSERT INTO distributors (did, dname) SELECT ?, ? ON CONFLICT ON CONSTRAINT distributors_pkey DO NOTHING" 1 "whatever"]
           ;; honeysql version:
           (-> (insert-into :distributors
                            [:did :dname]
                            (select 1 "whatever"))
               (on-conflict (on-constraint :distributors_pkey))
               do-nothing
               sql/format)
           ;; nilenso version:
           #_(-> (insert-into :distributors)
                 (columns :did :dname)
                 (query-values (select 1 "whatever"))
                 (upsert (-> (on-conflict-constraint :distributors_pkey)
                             do-nothing))
                 sql/format)))))

(deftest upsert-where-test
  (is (= ["INSERT INTO user (phone, name) VALUES (?, ?) ON CONFLICT (phone) WHERE phone IS NOT NULL DO UPDATE SET phone = EXCLUDED.phone, name = EXCLUDED.name WHERE user.active = FALSE" "5555555" "John"]
         (sql/format
          {:insert-into :user
           :values      [{:phone "5555555" :name "John"}]
           :on-conflict   [:phone
                           {:where [:<> :phone nil]}]
           :do-update-set {:fields [:phone :name]
                           :where  [:= :user.active false]}})
         ;; nilenso version
         #_(sql/format
            {:insert-into :user
             :values      [{:phone "5555555" :name "John"}]
             ;; nested under :upsert
             :upsert      {:on-conflict   [:phone]
                           ;; but :where is at the same level as :on-conflict
                           :where         [:<> :phone nil]
                           ;; this is the same as in honeysql:
                           :do-update-set {:fields [:phone :name]
                                           :where  [:= :user.active false]}}}))))

(deftest returning-test
  (testing "returning clause in sql generation for postgresql"
    (is (= ["DELETE FROM distributors WHERE did > 10 RETURNING *"]
           (sql/format {:delete-from :distributors
                        :where [:> :did :10]
                        :returning [:*]})))
    (is (= ["UPDATE distributors SET dname = ? WHERE did = 2 RETURNING did dname" "Foo Bar Designs"]
           (-> (update :distributors)
               (set {:dname "Foo Bar Designs"})
               (where [:= :did :2])
               (returning [:did :dname])
               sql/format)))))

(deftest create-view-test
  (testing "creating a view from a table"
    (is (= ["CREATE VIEW metro AS SELECT * FROM cities WHERE metroflag = ?" "Y"]
           (-> (create-view :metro)
               (select :*)
               (from :cities)
               (where [:= :metroflag "Y"])
               sql/format)))))

(deftest drop-table-test
  (testing "drop table sql generation for a single table"
    (is (= ["DROP TABLE cities"]
           (sql/format (drop-table :cities)))))
  (testing "drop table sql generation for multiple tables"
    (is (= ["DROP TABLE cities, towns, vilages"]
           (sql/format (drop-table :cities :towns :vilages))))))

(deftest create-table-test
  ;; the nilenso versions of these tests required sql/call for function-like syntax
  (testing "create table with two columns"
    (is (= ["CREATE TABLE cities (city VARCHAR(80) PRIMARY KEY, location POINT)"]
           (-> (create-table :cities)
               (with-columns [[:city [:varchar 80] [:primary-key]]
                              [:location :point]])
               sql/format))))
  (testing "create table with foreign key reference"
    (is (= ["CREATE TABLE weather (city VARCHAR(80) REFERENCES CITIES(CITY), temp_lo INT, temp_hi INT, prcp REAL, date DATE)"]
           (-> (create-table :weather)
               (with-columns [[:city [:varchar :80] [:references :cities :city]]
                              [:temp_lo :int]
                              [:temp_hi :int]
                              [:prcp :real]
                              [:date :date]])
               sql/format))))
  (testing "creating table with table level constraint"
    (is (= ["CREATE TABLE films (code CHAR(5), title VARCHAR(40), did INTEGER, date_prod DATE, kind VARCHAR(10), CONSTRAINT code_title PRIMARY KEY(CODE, TITLE))"]
           (-> (create-table :films)
               (with-columns [[:code [:char 5]]
                              [:title [:varchar 40]]
                              [:did :integer]
                              [:date_prod :date]
                              [:kind [:varchar 10]]
                              [[:constraint :code_title] [:primary-key :code :title]]])
               sql/format))))
  (testing "creating table with column level constraint"
    (is (= ["CREATE TABLE films (code CHAR(5) CONSTRAINT FIRSTKEY PRIMARY KEY, title VARCHAR(40) NOT NULL, did INTEGER NOT NULL, date_prod DATE, kind VARCHAR(10))"]
           (-> (create-table :films)
               (with-columns [[:code [:char 5] [:constraint :firstkey] [:primary-key]]
                              [:title [:varchar 40] [:not nil]]
                              [:did :integer [:not nil]]
                              [:date_prod :date]
                              [:kind [:varchar 10]]])
               sql/format))))
  (testing "creating table with columns with default values"
    (is (= ["CREATE TABLE distributors (did INTEGER PRIMARY KEY DEFAULT NEXTVAL('SERIAL'), name VARCHAR(40) NOT NULL)"]
           (-> (create-table :distributors)
               (with-columns [[:did :integer [:primary-key] [:default [:nextval "serial"]]]
                              [:name [:varchar 40] [:not nil]]])
               sql/format))))
  (testing "creating table with column checks"
    (is (= ["CREATE TABLE products (product_no INTEGER, name TEXT, price NUMERIC CHECK(PRICE > 0), discounted_price NUMERIC, CHECK((discounted_price > 0) AND (price > discounted_price)))"]
           (-> (create-table :products)
               (with-columns [[:product_no :integer]
                              [:name :text]
                              [:price :numeric [:check [:> :price 0]]]
                              [:discounted_price :numeric]
                              [[:check [:and [:> :discounted_price 0] [:> :price :discounted_price]]]]])
               sql/format)))))

(deftest over-test
  (testing "window function over on select statemt"
    (is (= ["SELECT id, AVG(salary) OVER (PARTITION BY department ORDER BY designation ASC) AS Average, MAX(salary) OVER w AS MaxSalary FROM employee WINDOW w AS (PARTITION BY department)"]
           ;; honeysql treats over as a function:
           (-> (select :id
                       (over
                        [[:avg :salary] (-> (partition-by :department) (order-by [:designation])) :Average]
                        [[:max :salary] :w :MaxSalary]))
               (from :employee)
               (window :w (partition-by :department))
               sql/format)
           ;; nilenso treated over as a clause
           #_(-> (select :id)
                 (over
                  [[:avg :salary] (-> (partition-by :department) (order-by [:designation])) :Average]
                  [[:max :salary] :w :MaxSalary])
                 (from :employee)
                 (window :w (partition-by :department))
                 sql/format)))))

(deftest alter-table-test
  (testing "alter table add column generates the required sql"
    (is (= ["ALTER TABLE employees ADD COLUMN address TEXT"]
           (-> (alter-table :employees)
               (add-column :address :text)
               sql/format))))
  (testing "alter table drop column generates the required sql"
    (is (= ["ALTER TABLE employees DROP COLUMN address"]
           (-> (alter-table :employees)
               (drop-column :address)
               sql/format))))
  (testing "alter table rename column generates the requred sql"
    (is (= ["ALTER TABLE employees RENAME COLUMN address TO homeaddress"]
           (-> (alter-table :employees)
               (rename-column :address :homeaddress)
               sql/format))))
  (testing "alter table rename table generates the required sql"
    (is (= ["ALTER TABLE employees RENAME TO managers"]
           (-> (alter-table :employees)
               (rename-table :managers)
               sql/format)))))

(deftest insert-into-with-alias
  (testing "insert into with alias"
    (is (= ["INSERT INTO distributors AS d (did, dname) VALUES (?, ?), (?, ?) ON CONFLICT (did) DO UPDATE SET dname = EXCLUDED.dname WHERE d.zipcode <> ? RETURNING d.*" 5 "Gizmo Transglobal" 6 "Associated Computing, Inc" "21201"]
           ;; honeysql supports alias in insert-into:
           (-> (insert-into :distributors :d)
               ;; nilensor required insert-into-as:
               #_(insert-into-as :distributors :d)
               (values [{:did 5 :dname "Gizmo Transglobal"}
                        {:did 6 :dname "Associated Computing, Inc"}])
               (on-conflict :did)
               ;; honeysql supports names and a where clause:
               (do-update-set :dname (where [:<> :d.zipcode "21201"]))
               ;; nilenso nested those under upsert:
               #_(upsert (-> (on-conflict :did)
                             (do-update-set :dname)
                             (where [:<> :d.zipcode "21201"])))
               (returning :d.*)
               sql/format)))))

(deftest create-table-if-not-exists
  (testing "create a table if not exists"
    (is (= ["CREATE TABLE IF NOT EXISTS tablename"]
           (-> (create-table :tablename :if-not-exists)
               sql/format)))))

(deftest drop-table-if-exists
  (testing "drop a table if it exists"
    (is (= ["DROP TABLE IF EXISTS t1, t2, t3"]
           (-> (drop-table :if-exists :t1 :t2 :t3)
               sql/format)))))

(deftest select-where-ilike
  (testing "select from table with ILIKE operator"
    (is (= ["SELECT * FROM products WHERE name ILIKE ?" "%name%"]
           (-> (select :*)
               (from :products)
               (where [:ilike :name "%name%"])
               sql/format)))))

(deftest select-where-not-ilike
  (testing "select from table with NOT ILIKE operator"
    (is (= ["SELECT * FROM products WHERE name NOT ILIKE ?" "%name%"]
           (-> (select :*)
               (from :products)
               (where [:not-ilike :name "%name%"])
               sql/format)))))

(deftest values-except-select
  (testing "select which values are not not present in a table"
    (is (= ["VALUES (?), (?), (?) EXCEPT SELECT id FROM images" 4 5 6]
           (sql/format
            {:except
             [{:values [[4] [5] [6]]}
              {:select [:id] :from [:images]}]})))))

(deftest select-except-select
  (testing "select which rows are not present in another table"
    (is (= ["SELECT ip EXCEPT SELECT ip FROM ip_location"]
           (sql/format
            {:except
             [{:select [:ip]}
              {:select [:ip] :from [:ip_location]}]})))))

(deftest values-except-all-select
  (testing "select which values are not not present in a table"
    (is (= ["VALUES (?), (?), (?) EXCEPT ALL SELECT id FROM images" 4 5 6]
           (sql/format
            {:except-all
             [{:values [[4] [5] [6]]}
              {:select [:id] :from [:images]}]})))))

(deftest select-except-all-select
  (testing "select which rows are not present in another table"
    (is (= ["SELECT ip EXCEPT ALL SELECT ip FROM ip_location"]
           (sql/format
            {:except-all
             [{:select [:ip]}
              {:select [:ip] :from [:ip_location]}]})))))

(deftest select-distinct-on-test
  (testing "select distinct on"
    (is (= ["SELECT DISTINCT ON(\"a\", \"b\") \"c\" FROM \"products\""]
           ;; honeysql has select-distinct-on:
           (-> (select-distinct-on [:a :b] :c)
               (from :products)
               (sql/format {:quoted true}))
           ;; nilenso handled that via modifiers:
           #_(-> (select :c)
                 (from :products)
                 (modifiers :distinct-on :a :b)
                 (sql/format :quoting :ansi))))))

(deftest create-extension-test
  ;; previously, honeysql required :allow-dashed-names? true
  (testing "create extension"
    (is (= ["CREATE EXTENSION \"uuid-ossp\""]
           (-> (create-extension :uuid-ossp)
               (sql/format {:quoted true})))))
  (testing "create extension if not exists"
    (is (= ["CREATE EXTENSION IF NOT EXISTS \"uuid-ossp\""]
           (-> (create-extension :uuid-ossp :if-not-exists)
               (sql/format {:quoted true}))))))

(deftest drop-extension-test
  ;; previously, honeysql required :allow-dashed-names? true
  (testing "create extension"
    (is (= ["DROP EXTENSION \"uuid-ossp\""]
           (-> (drop-extension :uuid-ossp)
               (sql/format {:quoted true}))))))
