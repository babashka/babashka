(ns honeysql.core-test
  (:refer-clojure :exclude [format update])
  (:require [#?@(:clj [clojure.test :refer]
                 :cljs [cljs.test :refer-macros]) [deftest testing is]]
            [honeysql.core :as sql]
            [honeysql.format :as sql-f]
            [honeysql.helpers :refer [select modifiers from join left-join
                                      right-join full-join cross-join
                                      where group having
                                      order-by limit offset values columns
                                      insert-into with merge-where merge-having]]
            honeysql.format-test))

;; BB_TEST_PATCH: must explicitly set data readers
#?(:clj
   (do
     (require '[honeysql.types])
     (set! *data-readers* {'sql/call honeysql.types/read-sql-call
                           'sql/inline honeysql.types/read-sql-inline
                           'sql/raw honeysql.types/read-sql-raw
                           'sql/param honeysql.types/read-sql-param
                           'sql/array honeysql.types/read-sql-array
                           'sql/regularize honeysql.format/regularize})))

;; TODO: more tests

(deftest test-select
  (let [m1 (-> (with [:cte (-> (select :*)
                               (from :example)
                               (where [:= :example-column 0]))])
               (select :f.* :b.baz :c.quux [:b.bla :bla-bla]
                       :%now (sql/raw "@x := 10"))
               ;;(un-select :c.quux)
               (modifiers :distinct)
               (from [:foo :f] [:baz :b])
               (join :draq [:= :f.b :draq.x])
               (left-join [:clod :c] [:= :f.a :c.d])
               (right-join :bock [:= :bock.z :c.e])
               (full-join :beck [:= :beck.x :c.y])
               (where [:or
                       [:and [:= :f.a "bort"] [:not= :b.baz :?param1]]
                       [:< 1 2 3]
                       [:in :f.e [1 (sql/param :param2) 3]]
                       [:between :f.e 10 20]])
               ;;(merge-where [:not= nil :b.bla])
               (group :f.a)
               (having [:< 0 :f.e])
               (order-by [:b.baz :desc] :c.quux [:f.a :nulls-first])
               (limit 50)
               (offset 10))
        m2 {:with [[:cte {:select [:*]
                          :from [:example]
                          :where [:= :example-column 0]}]]
            :select [:f.* :b.baz :c.quux [:b.bla :bla-bla]
                     :%now (sql/raw "@x := 10")]
            ;;:un-select :c.quux
            :modifiers :distinct
            :from [[:foo :f] [:baz :b]]
            :join [:draq [:= :f.b :draq.x]]
            :left-join [[:clod :c] [:= :f.a :c.d]]
            :right-join [:bock [:= :bock.z :c.e]]
            :full-join [:beck [:= :beck.x :c.y]]
            :where [:or
                    [:and [:= :f.a "bort"] [:not= :b.baz :?param1]]
                    [:< 1 2 3]
                    [:in :f.e [1 (sql/param :param2) 3]]
                    [:between :f.e 10 20]]
            ;;:merge-where [:not= nil :b.bla]
            :group-by :f.a
            :having [:< 0 :f.e]
            :order-by [[:b.baz :desc] :c.quux [:f.a :nulls-first]]
            :limit 50
            :offset 10}
        m3 (sql/build m2)
        m4 (apply sql/build (apply concat m2))]
    (testing "Various construction methods are consistent"
      (is (= m1 m3 m4)))
    (testing "SQL data formats correctly"
      (is (= ["WITH cte AS (SELECT * FROM example WHERE example_column = ?) SELECT DISTINCT f.*, b.baz, c.quux, b.bla AS bla_bla, now(), @x := 10 FROM foo f, baz b INNER JOIN draq ON f.b = draq.x LEFT JOIN clod c ON f.a = c.d RIGHT JOIN bock ON bock.z = c.e FULL JOIN beck ON beck.x = c.y WHERE ((f.a = ? AND b.baz <> ?) OR (? < ? AND ? < ?) OR (f.e in (?, ?, ?)) OR f.e BETWEEN ? AND ?) GROUP BY f.a HAVING ? < f.e ORDER BY b.baz DESC, c.quux, f.a NULLS FIRST LIMIT ? OFFSET ? "
              0 "bort" "gabba" 1 2 2 3 1 2 3 10 20 0 50 10]
             (sql/format m1 {:param1 "gabba" :param2 2}))))
    #?(:clj (testing "SQL data prints and reads correctly"
              (is (= m1 (read-string (pr-str m1))))))
    (testing "SQL data formats correctly with alternate param naming"
      (is (= (sql/format m1 :params {:param1 "gabba" :param2 2} :parameterizer :postgresql)
             ["WITH cte AS (SELECT * FROM example WHERE example_column = $1) SELECT DISTINCT f.*, b.baz, c.quux, b.bla AS bla_bla, now(), @x := 10 FROM foo f, baz b INNER JOIN draq ON f.b = draq.x LEFT JOIN clod c ON f.a = c.d RIGHT JOIN bock ON bock.z = c.e FULL JOIN beck ON beck.x = c.y WHERE ((f.a = $2 AND b.baz <> $3) OR ($4 < $5 AND $6 < $7) OR (f.e in ($8, $9, $10)) OR f.e BETWEEN $11 AND $12) GROUP BY f.a HAVING $13 < f.e ORDER BY b.baz DESC, c.quux, f.a NULLS FIRST LIMIT $14 OFFSET $15 "
              0 "bort" "gabba" 1 2 2 3 1 2 3 10 20 0 50 10])))
    (testing "Locking"
      (is (= ["WITH cte AS (SELECT * FROM example WHERE example_column = ?) SELECT DISTINCT f.*, b.baz, c.quux, b.bla AS bla_bla, now(), @x := 10 FROM foo f, baz b INNER JOIN draq ON f.b = draq.x LEFT JOIN clod c ON f.a = c.d RIGHT JOIN bock ON bock.z = c.e FULL JOIN beck ON beck.x = c.y WHERE ((f.a = ? AND b.baz <> ?) OR (? < ? AND ? < ?) OR (f.e in (?, ?, ?)) OR f.e BETWEEN ? AND ?) GROUP BY f.a HAVING ? < f.e ORDER BY b.baz DESC, c.quux, f.a NULLS FIRST LIMIT ? OFFSET ? FOR UPDATE "
              0 "bort" "gabba" 1 2 2 3 1 2 3 10 20 0 50 10]
             (sql/format (assoc m1 :lock {:mode :update})
                         {:param1 "gabba" :param2 2}))))))

(deftest test-cast
  (is (= ["SELECT foo, CAST(bar AS integer)"]
         (sql/format {:select [:foo (sql/call :cast :bar :integer)]})))
  (is (= ["SELECT foo, CAST(bar AS integer)"]
         (sql/format {:select [:foo (sql/call :cast :bar 'integer)]}))))

(deftest test-value
  (is (= ["INSERT INTO foo (bar) VALUES (?)" {:baz "my-val"}]
         (->
           (insert-into :foo)
           (columns :bar)
           (values [[(sql-f/value {:baz "my-val"})]])
           sql/format)))
  (is (= ["INSERT INTO foo (a, b, c) VALUES (?, ?, ?), (?, ?, ?)"
          "a" "b" "c" "a" "b" "c"]
         (-> (insert-into :foo)
             (values [(array-map :a "a" :b "b" :c "c")
                      (hash-map :a "a" :b "b" :c "c")])
             sql/format))))

(deftest test-operators
  (testing "="
    (testing "with nil"
      (is (= ["SELECT * FROM customers WHERE name IS NULL"]
             (sql/format {:select [:*]
                          :from [:customers]
                          :where [:= :name nil]})))
      (is (= ["SELECT * FROM customers WHERE name = ?" nil]
             (sql/format {:select [:*]
                          :from [:customers]
                          :where [:= :name :?name]}
                         {:name nil})))))
  (testing "in"
    (doseq [[cname coll] [[:vector []] [:set #{}] [:list '()]]]
      (testing (str "with values from a " (name cname))
        (let [values (conj coll 1)]
          (is (= ["SELECT * FROM customers WHERE (id in (?))" 1]
                 (sql/format {:select [:*]
                              :from [:customers]
                              :where [:in :id values]})))
          (is (= ["SELECT * FROM customers WHERE (id in (?))" 1]
                 (sql/format {:select [:*]
                              :from [:customers]
                              :where [:in :id :?ids]}
                             {:ids values}))))))
    (testing "with more than one integer"
      (let [values [1 2]]
        (is (= ["SELECT * FROM customers WHERE (id in (?, ?))" 1 2]
               (sql/format {:select [:*]
                            :from [:customers]
                            :where [:in :id values]})))
        (is (= ["SELECT * FROM customers WHERE (id in (?, ?))" 1 2]
               (sql/format {:select [:*]
                            :from [:customers]
                            :where [:in :id :?ids]}
                           {:ids values})))))
    (testing "with more than one string"
      (let [values ["1" "2"]]
        (is (= ["SELECT * FROM customers WHERE (id in (?, ?))" "1" "2"]
               (sql/format {:select [:*]
                            :from [:customers]
                            :where [:in :id values]})
               (sql/format {:select [:*]
                            :from [:customers]
                            :where [:in :id :?ids]}
                           {:ids values})))))))

(deftest test-case
  (is (= ["SELECT CASE WHEN foo < ? THEN ? WHEN (foo > ? AND (foo mod ?) = ?) THEN (foo / ?) ELSE ? END FROM bar"
          0 -1 0 2 0 2 0]
         (sql/format
          {:select [(sql/call
                     :case
                     [:< :foo 0] -1
                     [:and [:> :foo 0] [:= (sql/call :mod :foo 2) 0]] (sql/call :/ :foo 2)
                     :else 0)]
           :from [:bar]})))
  (let [param1 1
        param2 2
        param3 "three"]
    (is (= ["SELECT CASE WHEN foo = ? THEN ? WHEN foo = bar THEN ? WHEN bar = ? THEN (bar * ?) ELSE ? END FROM baz"
            param1 0 param2 0 param3 "param4"]
           (sql/format
            {:select [(sql/call
                       :case
                       [:= :foo :?param1] 0
                       [:= :foo :bar] (sql/param :param2)
                       [:= :bar 0] (sql/call :* :bar :?param3)
                       :else "param4")]
             :from [:baz]}
            {:param1 param1
             :param2 param2
             :param3 param3})))))

(deftest test-raw
  (is (= ["SELECT 1 + 1 FROM foo"]
         (-> (select (sql/raw "1 + 1"))
             (from :foo)
             sql/format))))

(deftest test-call
  (is (= ["SELECT min(?) FROM ?" "time" "table"]
         (-> (select (sql/call :min "time"))
             (from "table")
             sql/format))))

(deftest join-test
  (testing "nil join"
    (is (= ["SELECT * FROM foo INNER JOIN x ON foo.id = x.id INNER JOIN y"]
           (-> (select :*)
               (from :foo)
               (join :x [:= :foo.id :x.id] :y nil)
               sql/format)))))

(deftest join-using-test
  (testing "nil join"
    (is (= ["SELECT * FROM foo INNER JOIN x USING (id) INNER JOIN y USING (foo, bar)"]
           (-> (select :*)
               (from :foo)
               (join :x [:using :id] :y [:using :foo :bar])
               sql/format)))))

(deftest inline-test
  (is (= ["SELECT * FROM foo WHERE id = 5"]
         (-> (select :*)
             (from :foo)
             (where [:= :id (sql/inline 5)])
             sql/format)))
  ;; testing for = NULL always fails in SQL -- this test is just to show
  ;; that an #inline nil should render as NULL (so make sure you only use
  ;; it in contexts where a literal NULL is acceptable!)
  (is (= ["SELECT * FROM foo WHERE id = NULL"]
         (-> (select :*)
             (from :foo)
             (where [:= :id (sql/inline nil)])
             sql/format))))

(deftest merge-where-no-params-test
  (doseq [[k [f merge-f]] {"WHERE"  [where merge-where]
                           "HAVING" [having merge-having]}]
    (testing "merge-where called with just the map as parameter - see #228"
      (let [sqlmap (-> (select :*)
                       (from :table)
                       (f [:= :foo :bar]))]
        (is (= [(str "SELECT * FROM table " k " foo = bar")]
               (sql/format (apply merge-f sqlmap []))))))))

(deftest merge-where-test
  (doseq [[k sql-keyword f merge-f] [[:where "WHERE" where merge-where]
                                     [:having "HAVING" having merge-having]]]
    (is (= [(str "SELECT * FROM table " sql-keyword " (foo = bar AND quuz = xyzzy)")]
           (-> (select :*)
               (from :table)
               (f [:= :foo :bar] [:= :quuz :xyzzy])
               sql/format)))
    (is (= [(str "SELECT * FROM table " sql-keyword " (foo = bar AND quuz = xyzzy)")]
           (-> (select :*)
               (from :table)
               (f [:= :foo :bar])
               (merge-f [:= :quuz :xyzzy])
               sql/format)))
    (testing "Should work when first arg isn't a map"
      (is (= {k [:and [:x] [:y]]}
             (merge-f [:x] [:y]))))
    (testing "Shouldn't use conjunction if there is only one clause in the result"
      (is (= {k [:x]}
             (merge-f {} [:x]))))
    (testing "Should be able to specify the conjunction type"
      (is (= {k [:or [:x] [:y]]}
             (merge-f {}
                      :or
                      [:x] [:y]))))
    (testing "Should ignore nil clauses"
      (is (= {k [:or [:x] [:y]]}
             (merge-f {}
                      :or
                      [:x] nil [:y]))))))

(deftest merge-where-build-clause-test
  (doseq [k [:where :having]]
    (testing (str "Should be able to build a " k " clause with sql/build")
      (is (= {k [:and [:a] [:x] [:y]]}
             (sql/build
              k [:a]
              (keyword (str "merge-" (name k))) [:and [:x] [:y]]))))))

(deftest merge-where-combine-clauses-test
  (doseq [[k f] {:where  merge-where
                 :having merge-having}]
    (testing (str "Combine new " k " clauses into the existing clause when appropriate. (#282)")
      (testing "No existing clause"
        (is (= {k [:and [:x] [:y]]}
               (f {}
                  [:x] [:y]))))
      (testing "Existing clause is not a conjunction."
        (is (= {k [:and [:a] [:x] [:y]]}
               (f {k [:a]}
                  [:x] [:y]))))
      (testing "Existing clause IS a conjunction."
        (testing "New clause(s) are not conjunctions"
          (is (= {k [:and [:a] [:b] [:x] [:y]]}
                 (f {k [:and [:a] [:b]]}
                    [:x] [:y]))))
        (testing "New clauses(s) ARE conjunction(s)"
          (is (= {k [:and [:a] [:b] [:x] [:y]]}
                 (f {k [:and [:a] [:b]]}
                    [:and [:x] [:y]])))
          (is (= {k [:and [:a] [:b] [:x] [:y]]}
                 (f {k [:and [:a] [:b]]}
                    [:and [:x]]
                    [:y])))
          (is (= {k [:and [:a] [:b] [:x] [:y]]}
                 (f {k [:and [:a] [:b]]}
                    [:and [:x]]
                    [:and [:y]])))))
      (testing "if existing clause isn't the same conjunction, don't merge into it"
        (testing "existing conjunction is `:or`"
          (is (= {k [:and [:or [:a] [:b]] [:x] [:y]]}
                 (f {k [:or [:a] [:b]]}
                    [:x] [:y]))))
        (testing "pass conjunction type as a param (override default of :and)"
          (is (= {k [:or [:and [:a] [:b]] [:x] [:y]]}
                 (f {k [:and [:a] [:b]]}
                    :or
                    [:x] [:y]))))))))

(deftest where-nil-params-test
  (doseq [[_ sql-keyword f] [[:where "WHERE" where]
                             [:having "HAVING" having]]]
    (testing (str sql-keyword " called with nil parameters - see #246")
      (is (= [(str "SELECT * FROM table " sql-keyword " (foo = bar AND quuz = xyzzy)")]
             (-> (select :*)
                 (from :table)
                 (f nil [:= :foo :bar] nil [:= :quuz :xyzzy] nil)
                 sql/format)))
      (is (= ["SELECT * FROM table"]
             (-> (select :*)
                 (from :table)
                 (f)
                 sql/format)))
      (is (= ["SELECT * FROM table"]
             (-> (select :*)
                 (from :table)
                 (f nil nil nil nil)
                 sql/format))))))

(deftest cross-join-test
  (is (= ["SELECT * FROM foo CROSS JOIN bar"]
         (-> (select :*)
             (from :foo)
             (cross-join :bar)
             sql/format)))
  (is (= ["SELECT * FROM foo f CROSS JOIN bar b"]
         (-> (select :*)
             (from [:foo :f])
             (cross-join [:bar :b])
             sql/format))))

#?(:cljs (cljs.test/run-all-tests))
