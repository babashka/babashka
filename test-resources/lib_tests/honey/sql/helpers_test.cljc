;; copyright (c) 2020-2021 sean corfield, all rights reserved

(ns honey.sql.helpers-test
  (:refer-clojure :exclude [filter for group-by partition-by set update])
  (:require [clojure.test :refer [deftest is testing]]
            [honey.sql :as sql]
            [honey.sql.helpers :as h
             :refer [add-column add-index alter-table columns create-table create-table-as create-view
                     create-materialized-view drop-view drop-materialized-view
                     bulk-collect-into
                     cross-join do-update-set drop-column drop-index drop-table
                     filter from full-join
                     group-by having insert-into
                     join-by join lateral left-join limit offset on-conflict
                     on-duplicate-key-update
                     order-by over partition-by refresh-materialized-view
                     rename-column rename-table returning right-join
                     select select-distinct select-top select-distinct-top
                     values where window with with-columns
                     with-data within-group]]))

(deftest test-select
  (testing "large helper expression"
    (let [m1 (-> (with [:cte (-> (select :*)
                                 (from :example)
                                 (where [:= :example-column 0]))])
                 (select-distinct :f.* :b.baz :c.quux [:b.bla "bla-bla"]
                                  :%now [[:raw "@x := 10"]])
                 (from [:foo :f] [:baz :b])
                 (join :draq [:= :f.b :draq.x])
                 (left-join [:clod :c] [:= :f.a :c.d])
                 (right-join :bock [:= :bock.z :c.e])
                 (full-join :beck [:= :beck.x :c.y])
                 (where [:or
                         [:and [:= :f.a "bort"] [:not= :b.baz :?param1]]
                         [:and [:< 1 2] [:< 2 3]]
                         [:in :f.e [1 [:param :param2] 3]]
                         [:between :f.e 10 20]])
                 (group-by :f.a)
                 (having [:< 0 :f.e])
                 (order-by [:b.baz :desc] :c.quux [:f.a :nulls-first])
                 (limit 50)
                 (offset 10))
          m2 {:with [[:cte {:select [:*]
                            :from [:example]
                            :where [:= :example-column 0]}]]
              :select-distinct [:f.* :b.baz :c.quux [:b.bla "bla-bla"]
                                :%now [[:raw "@x := 10"]]]
              :from [[:foo :f] [:baz :b]]
              :join [:draq [:= :f.b :draq.x]]
              :left-join [[:clod :c] [:= :f.a :c.d]]
              :right-join [:bock [:= :bock.z :c.e]]
              :full-join [:beck [:= :beck.x :c.y]]
              :where [:or
                      [:and [:= :f.a "bort"] [:not= :b.baz :?param1]]
                      [:and [:< 1 2] [:< 2 3]]
                      [:in :f.e [1 [:param :param2] 3]]
                      [:between :f.e 10 20]]
              :group-by [:f.a]
              :having [:< 0 :f.e]
              :order-by [[:b.baz :desc] :c.quux [:f.a :nulls-first]]
              :limit 50
              :offset 10}]
      (testing "Various construction methods are consistent"
        (is (= m1 m2)))
      (testing "SQL data formats correctly"
        (is (= ["WITH cte AS (SELECT * FROM example WHERE example_column = ?) SELECT DISTINCT f.*, b.baz, c.quux, b.bla AS \"bla-bla\", NOW(), @x := 10 FROM foo AS f, baz AS b INNER JOIN draq ON f.b = draq.x LEFT JOIN clod AS c ON f.a = c.d RIGHT JOIN bock ON bock.z = c.e FULL JOIN beck ON beck.x = c.y WHERE ((f.a = ?) AND (b.baz <> ?)) OR ((? < ?) AND (? < ?)) OR (f.e IN (?, ?, ?)) OR f.e BETWEEN ? AND ? GROUP BY f.a HAVING ? < f.e ORDER BY b.baz DESC, c.quux ASC, f.a NULLS FIRST LIMIT ? OFFSET ?"
                0 "bort" "gabba" 1 2 2 3 1 2 3 10 20 0 50 10]
               (sql/format m1 {:params {:param1 "gabba" :param2 2}}))))
      #?(:clj (testing "SQL data prints and reads correctly"
                (is (= m1 (read-string (pr-str m1))))))
      #_(testing "SQL data formats correctly with alternate param naming"
          (is (= (sql/format m1 {:params {:param1 "gabba" :param2 2}})
                 ["WITH cte AS (SELECT * FROM example WHERE example_column = $1) SELECT DISTINCT f.*, b.baz, c.quux, b.bla \"bla-bla\", NOW(), @x := 10 FROM foo AS f, baz AS b INNER JOIN draq ON f.b = draq.x LEFT JOIN clod AS c ON f.a = c.d RIGHT JOIN bock ON bock.z = c.e FULL JOIN beck ON beck.x = c.y WHERE ((f.a = $2) AND (b.baz <> $3)) OR (($4 < $5) AND ($6 < $7)) OR (f.e IN ($8, $9, $10)) OR f.e BETWEEN $11 AND $12 GROUP BY f.a HAVING $13 < f.e ORDER BY b.baz DESC, c.quux ASC, f.a NULLS FIRST LIMIT $14 OFFSET $15"
                  0 "bort" "gabba" 1 2 2 3 1 2 3 10 20 0 50 10])))
      (testing "Locking"
        (is (= ["WITH cte AS (SELECT * FROM example WHERE example_column = ?) SELECT DISTINCT f.*, b.baz, c.quux, b.bla AS `bla-bla`, NOW(), @x := 10 FROM foo AS f, baz AS b INNER JOIN draq ON f.b = draq.x LEFT JOIN clod AS c ON f.a = c.d RIGHT JOIN bock ON bock.z = c.e FULL JOIN beck ON beck.x = c.y WHERE ((f.a = ?) AND (b.baz <> ?)) OR ((? < ?) AND (? < ?)) OR (f.e IN (?, ?, ?)) OR f.e BETWEEN ? AND ? GROUP BY f.a HAVING ? < f.e ORDER BY b.baz DESC, c.quux ASC, f.a NULLS FIRST LIMIT ? OFFSET ? LOCK IN SHARE MODE"
                0 "bort" "gabba" 1 2 2 3 1 2 3 10 20 0 50 10]
               (sql/format (assoc m1 :lock [:in-share-mode])
                           {:params {:param1 "gabba" :param2 2}
                            ;; to enable :lock
                            :dialect :mysql :quoted false}))))))
  (testing "large helper expression with simplified where"
    (let [m1 (-> (with [:cte (-> (select :*)
                                 (from :example)
                                 (where := :example-column 0))])
                 (select-distinct :f.* :b.baz :c.quux [:b.bla "bla-bla"]
                                  :%now [[:raw "@x := 10"]])
                 (from [:foo :f] [:baz :b])
                 (join :draq [:= :f.b :draq.x])
                 (left-join [:clod :c] [:= :f.a :c.d])
                 (right-join :bock [:= :bock.z :c.e])
                 (full-join :beck [:= :beck.x :c.y])
                 (where :or
                        [:and [:= :f.a "bort"] [:not= :b.baz :?param1]]
                        [:and [:< 1 2] [:< 2 3]]
                        [:in :f.e [1 [:param :param2] 3]]
                        [:between :f.e 10 20])
                 (group-by :f.a)
                 (having :< 0 :f.e)
                 (order-by [:b.baz :desc] :c.quux [:f.a :nulls-first])
                 (limit 50)
                 (offset 10))
          m2 {:with [[:cte {:select [:*]
                            :from [:example]
                            :where [:= :example-column 0]}]]
              :select-distinct [:f.* :b.baz :c.quux [:b.bla "bla-bla"]
                                :%now [[:raw "@x := 10"]]]
              :from [[:foo :f] [:baz :b]]
              :join [:draq [:= :f.b :draq.x]]
              :left-join [[:clod :c] [:= :f.a :c.d]]
              :right-join [:bock [:= :bock.z :c.e]]
              :full-join [:beck [:= :beck.x :c.y]]
              :where [:or
                      [:and [:= :f.a "bort"] [:not= :b.baz :?param1]]
                      [:and [:< 1 2] [:< 2 3]]
                      [:in :f.e [1 [:param :param2] 3]]
                      [:between :f.e 10 20]]
              :group-by [:f.a]
              :having [:< 0 :f.e]
              :order-by [[:b.baz :desc] :c.quux [:f.a :nulls-first]]
              :limit 50
              :offset 10}]
      (testing "Various construction methods are consistent"
        (is (= m1 m2)))
      (testing "SQL data formats correctly"
        (is (= ["WITH cte AS (SELECT * FROM example WHERE example_column = ?) SELECT DISTINCT f.*, b.baz, c.quux, b.bla AS \"bla-bla\", NOW(), @x := 10 FROM foo AS f, baz AS b INNER JOIN draq ON f.b = draq.x LEFT JOIN clod AS c ON f.a = c.d RIGHT JOIN bock ON bock.z = c.e FULL JOIN beck ON beck.x = c.y WHERE ((f.a = ?) AND (b.baz <> ?)) OR ((? < ?) AND (? < ?)) OR (f.e IN (?, ?, ?)) OR f.e BETWEEN ? AND ? GROUP BY f.a HAVING ? < f.e ORDER BY b.baz DESC, c.quux ASC, f.a NULLS FIRST LIMIT ? OFFSET ?"
                0 "bort" "gabba" 1 2 2 3 1 2 3 10 20 0 50 10]
               (sql/format m1 {:params {:param1 "gabba" :param2 2}}))))
      #?(:clj (testing "SQL data prints and reads correctly"
                (is (= m1 (read-string (pr-str m1))))))
      #_(testing "SQL data formats correctly with alternate param naming"
          (is (= (sql/format m1 {:params {:param1 "gabba" :param2 2}})
                 ["WITH cte AS (SELECT * FROM example WHERE example_column = $1) SELECT DISTINCT f.*, b.baz, c.quux, b.bla \"bla-bla\", NOW(), @x := 10 FROM foo AS f, baz AS b INNER JOIN draq ON f.b = draq.x LEFT JOIN clod AS c ON f.a = c.d RIGHT JOIN bock ON bock.z = c.e FULL JOIN beck ON beck.x = c.y WHERE ((f.a = $2) AND (b.baz <> $3)) OR (($4 < $5) AND ($6 < $7)) OR (f.e IN ($8, $9, $10)) OR f.e BETWEEN $11 AND $12 GROUP BY f.a HAVING $13 < f.e ORDER BY b.baz DESC, c.quux ASC, f.a NULLS FIRST LIMIT $14 OFFSET $15"
                  0 "bort" "gabba" 1 2 2 3 1 2 3 10 20 0 50 10])))
      (testing "Locking"
        (is (= ["WITH cte AS (SELECT * FROM example WHERE example_column = ?) SELECT DISTINCT f.*, b.baz, c.quux, b.bla AS `bla-bla`, NOW(), @x := 10 FROM foo AS f, baz AS b INNER JOIN draq ON f.b = draq.x LEFT JOIN clod AS c ON f.a = c.d RIGHT JOIN bock ON bock.z = c.e FULL JOIN beck ON beck.x = c.y WHERE ((f.a = ?) AND (b.baz <> ?)) OR ((? < ?) AND (? < ?)) OR (f.e IN (?, ?, ?)) OR f.e BETWEEN ? AND ? GROUP BY f.a HAVING ? < f.e ORDER BY b.baz DESC, c.quux ASC, f.a NULLS FIRST LIMIT ? OFFSET ? LOCK IN SHARE MODE"
                0 "bort" "gabba" 1 2 2 3 1 2 3 10 20 0 50 10]
               (sql/format (assoc m1 :lock [:in-share-mode])
                           {:params {:param1 "gabba" :param2 2}
                            ;; to enable :lock
                            :dialect :mysql :quoted false})))))))

(deftest select-top-tests
  (testing "Basic TOP syntax"
    (is (= ["SELECT TOP(?) foo FROM bar ORDER BY quux ASC" 10]
           (sql/format {:select-top [10 :foo] :from :bar :order-by [:quux]})))
    (is (= ["SELECT TOP(?) foo FROM bar ORDER BY quux ASC" 10]
           (sql/format (-> (select-top 10 :foo)
                           (from :bar)
                           (order-by :quux))))))
  (testing "Expanded TOP syntax"
    (is (= ["SELECT TOP(?) PERCENT WITH TIES foo, baz FROM bar ORDER BY quux ASC" 10]
           (sql/format {:select-top [[10 :percent :with-ties] :foo :baz] :from :bar :order-by [:quux]})))
    (is (= ["SELECT TOP(?) PERCENT WITH TIES foo, baz FROM bar ORDER BY quux ASC" 10]
           (sql/format (-> (select-top [10 :percent :with-ties] :foo :baz)
                           (from :bar)
                           (order-by :quux)))))))

(deftest select-into-tests
  (testing "SELECT INTO"
    (is (= ["SELECT * INTO foo FROM bar"]
           (sql/format {:select :* :into :foo :from :bar})))
    (is (= ["SELECT * INTO foo IN otherdb FROM bar"]
           (sql/format {:select :* :into [:foo :otherdb] :from :bar})))
    (is (= ["SELECT * INTO foo FROM bar"]
           (sql/format (-> (select '*) (h/into 'foo) (from 'bar)))))
    (is (= ["SELECT * INTO foo IN otherdb FROM bar"]
           (sql/format (-> (select :*) (h/into :foo :otherdb) (from :bar))))))
  (testing "SELECT BULK COLLECT INTO"
    (is (= ["SELECT * BULK COLLECT INTO foo FROM bar"]
           (sql/format {:select :* :bulk-collect-into :foo :from :bar})))
    (is (= ["SELECT * BULK COLLECT INTO foo LIMIT ? FROM bar" 100]
           (sql/format {:select :* :bulk-collect-into [:foo 100] :from :bar})))
    (is (= ["SELECT * BULK COLLECT INTO foo FROM bar"]
           (sql/format (-> (select :*) (bulk-collect-into :foo) (from :bar)))))
    (is (= ["SELECT * BULK COLLECT INTO foo LIMIT ? FROM bar" 100]
           (sql/format (-> (select :*) (bulk-collect-into :foo 100) (from :bar)))))))

(deftest from-expression-tests
  (testing "FROM can be a function invocation"
    (is (= ["SELECT foo, bar FROM F(?) AS x" 1]
           (sql/format {:select [:foo :bar] :from [[[:f 1] :x]]}))))
  ;; these two examples are from https://www.postgresql.org/docs/9.3/queries-table-expressions.html#QUERIES-LATERAL
  (testing "FROM can be a LATERAL select"
    (is (= ["SELECT * FROM foo, LATERAL (SELECT * FROM bar WHERE bar.id = foo.bar_id) AS ss"]
           (sql/format {:select :*
                        :from [:foo
                               [[:lateral {:select :*
                                           :from :bar
                                           :where [:= :bar.id :foo.bar_id]}] :ss]]}))))
  (testing "FROM can be a LATERAL expression"
    (is (= [(str "SELECT p1.id, p2.id, v1, v2"
                 " FROM polygons AS p1, polygons AS p2,"
                 " LATERAL VERTICES(p1.poly) AS v1,"
                 " LATERAL VERTICES(p2.poly) AS v2"
                 " WHERE ((v1 <-> v2) < ?) AND (p1.id <> p2.id)") 10]
           (sql/format {:select [:p1.id :p2.id :v1 :v2]
                        :from [[:polygons :p1] [:polygons :p2]
                               [[:lateral [:vertices :p1.poly]] :v1]
                               [[:lateral [:vertices :p2.poly]] :v2]]
                        :where [:and [:< [:<-> :v1 :v2] 10] [:!= :p1.id :p2.id]]})))
    (is (= [(str "SELECT m.name"
                 " FROM manufacturers AS m"
                 " LEFT JOIN LATERAL GET_PRODUCT_NAMES(m.id) AS pname ON TRUE"
                 " WHERE pname IS NULL")]
           (sql/format {:select :m.name
                        :from [[:manufacturers :m]]
                        :left-join [[[:lateral [:get_product_names :m.id]] :pname] true]
                        :where [:= :pname nil]})))))

(deftest join-by-test
  (testing "Natural JOIN orders"
    (is (= ["SELECT * FROM foo INNER JOIN draq ON f.b = draq.x LEFT JOIN clod AS c ON f.a = c.d RIGHT JOIN bock ON bock.z = c.e FULL JOIN beck ON beck.x = c.y"]
           (sql/format {:select [:*] :from [:foo]
                        :full-join  [:beck [:= :beck.x :c.y]]
                        :right-join [:bock [:= :bock.z :c.e]]
                        :left-join  [[:clod :c] [:= :f.a :c.d]]
                        :join       [:draq [:= :f.b :draq.x]]}))))
  (testing "Specific JOIN orders"
    (is (= ["SELECT * FROM foo FULL JOIN beck ON beck.x = c.y RIGHT JOIN bock ON bock.z = c.e LEFT JOIN clod AS c ON f.a = c.d INNER JOIN draq ON f.b = draq.x"]
           (sql/format {:select [:*] :from [:foo]
                        :join-by [:full  [:beck [:= :beck.x :c.y]]
                                  :right [:bock [:= :bock.z :c.e]]
                                  :left  [[:clod :c] [:= :f.a :c.d]]
                                  :join  [:draq [:= :f.b :draq.x]]]})))
    (is (= ["SELECT * FROM foo FULL JOIN beck ON beck.x = c.y RIGHT JOIN bock ON bock.z = c.e LEFT JOIN clod AS c ON f.a = c.d INNER JOIN draq ON f.b = draq.x"]
           (-> (select :*)
               (from :foo)
               (join-by :full-join  [:beck [:= :beck.x :c.y]]
                        :right-join [:bock [:= :bock.z :c.e]]
                        :left-join  [[:clod :c] [:= :f.a :c.d]]
                        :inner-join [:draq [:= :f.b :draq.x]])
               (sql/format)))))
  (testing "Specific JOIN orders with join clauses"
    (is (= ["SELECT * FROM foo FULL JOIN beck ON beck.x = c.y RIGHT JOIN bock ON bock.z = c.e LEFT JOIN clod AS c ON f.a = c.d INNER JOIN draq ON f.b = draq.x"]
           (sql/format {:select [:*] :from [:foo]
                        :join-by [{:full-join [:beck [:= :beck.x :c.y]]}
                                  {:right-join [:bock [:= :bock.z :c.e]]}
                                  {:left-join [[:clod :c] [:= :f.a :c.d]]}
                                  {:join [:draq [:= :f.b :draq.x]]}]})))
    (is (= ["SELECT * FROM foo FULL JOIN beck ON beck.x = c.y RIGHT JOIN bock ON bock.z = c.e LEFT JOIN clod AS c ON f.a = c.d INNER JOIN draq ON f.b = draq.x"]
           (-> (select :*)
               (from :foo)
               (join-by (full-join :beck [:= :beck.x :c.y])
                        (right-join :bock [:= :bock.z :c.e])
                        (left-join [:clod :c] [:= :f.a :c.d])
                        (join :draq [:= :f.b :draq.x]))
               (sql/format))))))

(deftest test-cast
  (is (= ["SELECT foo, CAST(bar AS integer)"]
         (sql/format {:select [:foo [[:cast :bar :integer]]]})))
  (is (= ["SELECT foo, CAST(bar AS integer)"]
         (sql/format {:select [:foo [[:cast :bar 'integer]]]}))))

(deftest test-value
  (is (= ["INSERT INTO foo (bar) VALUES (?)" {:baz "my-val"}]
         (->
           (insert-into :foo)
           (columns :bar)
           (values [[[:lift {:baz "my-val"}]]])
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
                         {:params {:name nil}})))))
  (testing "in"
    (doseq [[cname coll] [[:vector []] [:set #{}] [:list '()]]]
      (testing (str "with values from a " (name cname))
        (let [values (conj coll 1)]
          (is (= ["SELECT * FROM customers WHERE id IN (?)" 1]
                 (sql/format {:select [:*]
                              :from [:customers]
                              :where [:in :id values]})))
          (is (= ["SELECT * FROM customers WHERE id IN (?)" 1]
                 (sql/format {:select [:*]
                              :from [:customers]
                              :where [:in :id :?ids]}
                             {:params {:ids values}}))))))
    (testing "with more than one integer"
      (let [values [1 2]]
        (is (= ["SELECT * FROM customers WHERE id IN (?, ?)" 1 2]
               (sql/format {:select [:*]
                            :from [:customers]
                            :where [:in :id values]})))
        (is (= ["SELECT * FROM customers WHERE id IN (?, ?)" 1 2]
               (sql/format {:select [:*]
                            :from [:customers]
                            :where [:in :id :?ids]}
                           {:params {:ids values}})))))
    (testing "with more than one string"
      (let [values ["1" "2"]]
        (is (= ["SELECT * FROM customers WHERE id IN (?, ?)" "1" "2"]
               (sql/format {:select [:*]
                            :from [:customers]
                            :where [:in :id values]})
               (sql/format {:select [:*]
                            :from [:customers]
                            :where [:in :id :?ids]}
                           {:params {:ids values}})))))))

(deftest test-case
  (is (= ["SELECT CASE WHEN foo < ? THEN ? WHEN (foo > ?) AND ((foo MOD ?) = ?) THEN foo / ? ELSE ? END FROM bar"
          0 -1 0 2 0 2 0]
         (sql/format
          {:select [[[:case
                      [:< :foo 0] -1
                      [:and [:> :foo 0] [:= [:mod :foo 2] 0]] [:/ :foo 2]
                      :else 0]]]
           :from [:bar]})))
  (let [param1 1
        param2 2
        param3 "three"]
    (is (= ["SELECT CASE WHEN foo = ? THEN ? WHEN foo = bar THEN ? WHEN bar = ? THEN bar * ? ELSE ? END FROM baz"
            param1 0 param2 0 param3 "param4"]
           (sql/format
            {:select [[[:case
                        [:= :foo :?param1] 0
                        [:= :foo :bar] [:param :param2]
                        [:= :bar 0] [:* :bar :?param3]
                        :else "param4"]]]
             :from [:baz]}
            {:params
             {:param1 param1
              :param2 param2
              :param3 param3}})))))

(deftest test-raw
  (is (= ["SELECT 1 + 1 FROM foo"]
         (-> (select [[:raw "1 + 1"]])
             (from :foo)
             sql/format))))

(deftest test-call
  (is (= ["SELECT MIN(?) FROM ?" "time" "table"]
         (-> (select [[:min "time"]])
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
             (where [:= :id [:inline 5]])
             sql/format)))
  ;; testing for = NULL always fails in SQL -- this test is just to show
  ;; that an #inline nil should render as NULL (so make sure you only use
  ;; it in contexts where a literal NULL is acceptable!)
  (is (= ["SELECT * FROM foo WHERE id = NULL"]
         (-> (select :*)
             (from :foo)
             (where [:= :id [:inline nil]])
             sql/format))))

(deftest where-no-params-test
  (testing "where called with just the map as parameter - see #228"
    (let [sqlmap (-> (select :*)
                     (from :table)
                     (where [:= :foo :bar]))]
      (is (= ["SELECT * FROM table WHERE foo = bar"]
             (sql/format (apply merge sqlmap [])))))))

(deftest where-test
  (is (= ["SELECT * FROM table WHERE (foo = bar) AND (quuz = xyzzy)"]
         (-> (select :*)
             (from :table)
             (where [:= :foo :bar] [:= :quuz :xyzzy])
             sql/format)))
  (is (= ["SELECT * FROM table WHERE (foo = bar) AND (quuz = xyzzy)"]
         (-> (select :*)
             (from :table)
             (where [:= :foo :bar])
             (where [:= :quuz :xyzzy])
             sql/format))))

(deftest where-nil-params-test
  (testing "where called with nil parameters - see #246"
    (is (= ["SELECT * FROM table WHERE (foo = bar) AND (quuz = xyzzy)"]
           (-> (select :*)
               (from :table)
               (where nil [:= :foo :bar] nil [:= :quuz :xyzzy] nil)
               sql/format)))
    (is (= ["SELECT * FROM table"]
           (-> (select :*)
               (from :table)
               (where)
               sql/format)))
    (is (= ["SELECT * FROM table"]
           (-> (select :*)
               (from :table)
               (where nil nil nil nil)
               sql/format)))))

(deftest cross-join-test
  (is (= ["SELECT * FROM foo CROSS JOIN bar"]
         (-> (select :*)
             (from :foo)
             (cross-join :bar)
             sql/format)))
  (is (= ["SELECT * FROM foo AS f CROSS JOIN bar b"]
         (-> (select :*)
             (from [:foo :f])
             (cross-join [:bar :b])
             sql/format))))

(defn- stack-overflow-282 [num-ids]
  (let [ids (range num-ids)]
    (sql/format (reduce
                 where
                 {:select [[:id :id]]
                  :from   [:collection]
                  :where  [:= :personal_owner_id nil]}
                 (clojure.core/for [id ids]
                                   [:not-like :location [:raw (str "'/" id "/%'")]])))))

(deftest issue-282
  (is (= [(str "SELECT id AS id FROM collection"
               " WHERE (personal_owner_id IS NULL)"
               " AND (location NOT LIKE '/0/%')"
               " AND (location NOT LIKE '/1/%')")]
         (stack-overflow-282 2))))

(deftest issue-293-sql
  ;; these tests are based on the README at https://github.com/nilenso/honeysql-postgres
  (is (= (-> (insert-into :distributors)
             (values [{:did 5 :dname "Gizmo Transglobal"}
                      {:did 6 :dname "Associated Computing, Inc"}])
             (-> (on-conflict :did)
                 (do-update-set :dname))
             (returning :*)
             sql/format)
         [(str "INSERT INTO distributors (did, dname)"
               " VALUES (?, ?), (?, ?)"
               " ON CONFLICT (did)"
               " DO UPDATE SET dname = EXCLUDED.dname"
               " RETURNING *")
          5 "Gizmo Transglobal"
          6 "Associated Computing, Inc"]))
  (is (= (-> (insert-into :distributors)
             (values [{:did 23 :dname "Foo Distributors"}])
             (on-conflict :did)
             ;; instead of do-update-set!
             (do-update-set {:dname [:|| :EXCLUDED.dname " (formerly " :distributors.dname ")"]
                             :downer :EXCLUDED.downer})
             sql/format)
         [(str "INSERT INTO distributors (did, dname)"
               " VALUES (?, ?)"
               " ON CONFLICT (did)"
               " DO UPDATE SET dname = EXCLUDED.dname || ? || distributors.dname || ?,"
               " downer = EXCLUDED.downer")
          23 "Foo Distributors" " (formerly " ")"]))
  ;; insert into / insert into as tests are below
  (is (= (-> (select :id
                     (over [[:avg :salary] (-> (partition-by :department) (order-by :designation)) :Average]
                           [[:max :salary] :w :MaxSalary]))
             (from :employee)
             (window :w (partition-by :department))
             sql/format)
         [(str "SELECT id,"
               " AVG(salary) OVER (PARTITION BY department ORDER BY designation ASC) AS Average,"
               " MAX(salary) OVER w AS MaxSalary"
               " FROM employee"
               " WINDOW w AS (PARTITION BY department)")]))
  ;; test nil / empty window function clause:
  (is (= (-> (select :id
                     (over [[:avg :salary] {} :Average]
                           [[:max :salary] nil :MaxSalary]))
             (from :employee)
             sql/format)
         [(str "SELECT id,"
               " AVG(salary) OVER () AS Average,"
               " MAX(salary) OVER () AS MaxSalary"
               " FROM employee")])))

(deftest issue-293-basic-ddl
  (is (= (sql/format {:create-view :metro :select [:*] :from [:cities] :where [:= :metroflag "y"]})
         ["CREATE VIEW metro AS SELECT * FROM cities WHERE metroflag = ?" "y"]))
  (is (= (sql/format {:create-table :films
                      :with-columns [[:id :int :unsigned :auto-increment]
                                     [:name [:varchar 50] [:not nil]]]})
         ["CREATE TABLE films (id INT UNSIGNED AUTO_INCREMENT, name VARCHAR(50) NOT NULL)"]))
  (is (= (sql/format (-> (create-view :metro)
                         (select :*)
                         (from :cities)
                         (where [:= :metroflag "y"])))
         ["CREATE VIEW metro AS SELECT * FROM cities WHERE metroflag = ?" "y"]))
  (is (= (sql/format (-> (create-table-as :metro :if-not-exists)
                         (select :*)
                         (from :cities)
                         (where [:= :metroflag "y"])
                         (with-data false)))
         ["CREATE TABLE IF NOT EXISTS metro AS SELECT * FROM cities WHERE metroflag = ? WITH NO DATA" "y"]))
  (is (= (sql/format (-> (create-materialized-view :metro :if-not-exists)
                         (select :*)
                         (from :cities)
                         (where [:= :metroflag "y"])
                         (with-data false)))
         ["CREATE MATERIALIZED VIEW IF NOT EXISTS metro AS SELECT * FROM cities WHERE metroflag = ? WITH NO DATA" "y"]))
  (is (= (sql/format (-> (create-table-as :metro :if-not-exists
                                          (columns :foo :bar :baz)
                                          [:tablespace [:entity :quux]])
                         (select :*)
                         (from :cities)
                         (where [:= :metroflag "y"])
                         (with-data false)))
         [(str "CREATE TABLE IF NOT EXISTS metro"
               " (foo, bar, baz) TABLESPACE quux"
               " AS SELECT * FROM cities WHERE metroflag = ? WITH NO DATA") "y"]))
  (is (= (sql/format (-> (create-materialized-view :metro :if-not-exists
                                                   (columns :foo :bar :baz)
                                                   [:tablespace [:entity :quux]])
                         (select :*)
                         (from :cities)
                         (where [:= :metroflag "y"])
                         (with-data false)))
         [(str "CREATE MATERIALIZED VIEW IF NOT EXISTS metro"
               " (foo, bar, baz) TABLESPACE quux"
               " AS SELECT * FROM cities WHERE metroflag = ? WITH NO DATA") "y"]))
  (is (= (sql/format {:create-materialized-view [:metro :if-not-exists]
                      :select [:*]
                      :from :cities
                      :where [:= :metroflag "y"]
                      :with-data true})
         ["CREATE MATERIALIZED VIEW IF NOT EXISTS metro AS SELECT * FROM cities WHERE metroflag = ? WITH DATA" "y"]))
  (is (= (sql/format {:create-materialized-view [:metro :if-not-exists
                                                 (columns :foo :bar :baz)
                                                 [:tablespace [:entity :quux]]]
                      :select [:*]
                      :from :cities
                      :where [:= :metroflag "y"]
                      :with-data false})
         [(str "CREATE MATERIALIZED VIEW IF NOT EXISTS metro"
               " (foo, bar, baz) TABLESPACE quux"
               " AS SELECT * FROM cities WHERE metroflag = ? WITH NO DATA") "y"]))
  (is (= (sql/format (-> (create-table :films)
                         (with-columns
                           [:id :int :unsigned :auto-increment]
                           [:name [:varchar 50] [:not nil]])))
         ["CREATE TABLE films (id INT UNSIGNED AUTO_INCREMENT, name VARCHAR(50) NOT NULL)"]))
  (is (= (sql/format (-> (create-table :films :if-not-exists)
                         (with-columns
                           [:id :int :unsigned :auto-increment]
                           [:name [:varchar 50] [:not nil]])))
         ["CREATE TABLE IF NOT EXISTS films (id INT UNSIGNED AUTO_INCREMENT, name VARCHAR(50) NOT NULL)"]))
  (is (= (sql/format (-> {:create-table :films
                          :with-columns
                          [[:id :int :unsigned :auto-increment]
                           [:name [:varchar 50] [:not nil]]]}))
         ["CREATE TABLE films (id INT UNSIGNED AUTO_INCREMENT, name VARCHAR(50) NOT NULL)"]))
  (is (= (sql/format (-> {:create-table [:films :if-not-exists]
                          :with-columns
                          [[:id :int :unsigned :auto-increment]
                           [:name [:varchar 50] [:not nil]]]}))
         ["CREATE TABLE IF NOT EXISTS films (id INT UNSIGNED AUTO_INCREMENT, name VARCHAR(50) NOT NULL)"]))
  (is (= (sql/format {:drop-table :foo})
         ["DROP TABLE foo"]))
  (is (= (sql/format {:drop-table [:if-exists :foo]})
         ["DROP TABLE IF EXISTS foo"]))
  (is (= (sql/format {:drop-view [:if-exists :foo]})
         ["DROP VIEW IF EXISTS foo"]))
  (is (= (sql/format {:drop-materialized-view [:if-exists :foo]})
         ["DROP MATERIALIZED VIEW IF EXISTS foo"]))
  (is (= (sql/format {:refresh-materialized-view [:concurrently :foo]
                      :with-data true})
         ["REFRESH MATERIALIZED VIEW CONCURRENTLY foo WITH DATA"]))
  (is (= (sql/format '{drop-table (if-exists foo)})
         ["DROP TABLE IF EXISTS foo"]))
  (is (= (sql/format {:drop-table [:foo :bar]})
         ["DROP TABLE foo, bar"]))
  (is (= (sql/format {:drop-table [:if-exists :foo :bar]})
         ["DROP TABLE IF EXISTS foo, bar"]))
  (is (= (sql/format {:drop-table [:if-exists :foo :bar [:cascade]]})
         ["DROP TABLE IF EXISTS foo, bar CASCADE"]))
  (is (= (sql/format (drop-table :foo))
         ["DROP TABLE foo"]))
  (is (= (sql/format (drop-table :if-exists :foo))
         ["DROP TABLE IF EXISTS foo"]))
  (is (= (sql/format (-> (refresh-materialized-view :concurrently :foo)
                         (with-data true)))
         ["REFRESH MATERIALIZED VIEW CONCURRENTLY foo WITH DATA"]))
  (is (= (sql/format (drop-table :foo :bar))
         ["DROP TABLE foo, bar"]))
  (is (= (sql/format (drop-table :if-exists :foo :bar [:cascade]))
         ["DROP TABLE IF EXISTS foo, bar CASCADE"])))

(deftest issue-293-alter-table
  (is (= (sql/format (-> (alter-table :fruit)
                         (add-column :id :int [:not nil])))
         ["ALTER TABLE fruit ADD COLUMN id INT NOT NULL"]))
  (is (= (sql/format (alter-table :fruit
                                  (add-column :id :int [:not nil])
                                  (drop-column :ident)))
         ["ALTER TABLE fruit ADD COLUMN id INT NOT NULL, DROP COLUMN ident"])))

(deftest issue-293-insert-into-data
  ;; insert into as (and other tests) based on :insert-into
  ;; examples in the clause reference docs:
  ;; first case -- table specifier:
  (is (= (sql/format {:insert-into :transport
                      :values [[1 "Car"] [2 "Boat"] [3 "Bike"]]})
         ["INSERT INTO transport VALUES (?, ?), (?, ?), (?, ?)" 1 "Car" 2 "Boat" 3 "Bike"]))
  (is (= (sql/format {:insert-into :transport
                      :columns [:id :name]
                      :values [[1 "Car"] [2 "Boat"] [3 "Bike"]]})
         ["INSERT INTO transport (id, name) VALUES (?, ?), (?, ?), (?, ?)" 1 "Car" 2 "Boat" 3 "Bike"]))
  ;; with an alias:
  (is (= (sql/format {:insert-into [:transport :t]
                      :values [[1 "Car"] [2 "Boat"] [3 "Bike"]]})
         ["INSERT INTO transport AS t VALUES (?, ?), (?, ?), (?, ?)" 1 "Car" 2 "Boat" 3 "Bike"]))
  (is (= (sql/format {:insert-into [:transport :t]
                      :columns [:id :name]
                      :values [[1 "Car"] [2 "Boat"] [3 "Bike"]]})
         ["INSERT INTO transport AS t (id, name) VALUES (?, ?), (?, ?), (?, ?)" 1 "Car" 2 "Boat" 3 "Bike"]))
  ;; second case -- table specifier and columns:
  (is (= (sql/format {:insert-into [:transport [:id :name]]
                      :values [[1 "Car"] [2 "Boat"] [3 "Bike"]]})
         ["INSERT INTO transport (id, name) VALUES (?, ?), (?, ?), (?, ?)" 1 "Car" 2 "Boat" 3 "Bike"]))
  ;; with an alias:
  (is (= (sql/format {:insert-into [[:transport :t] [:id :name]]
                      :values [[1 "Car"] [2 "Boat"] [3 "Bike"]]})
         ["INSERT INTO transport AS t (id, name) VALUES (?, ?), (?, ?), (?, ?)" 1 "Car" 2 "Boat" 3 "Bike"]))
  ;; third case -- table/column specifier and query:
  (is (= (sql/format '{insert-into (transport {select (id, name) from (cars)})})
         ["INSERT INTO transport SELECT id, name FROM cars"]))
  ;; with columns:
  (is (= (sql/format '{insert-into ((transport (id, name)) {select (*) from (cars)})})
         ["INSERT INTO transport (id, name) SELECT * FROM cars"]))
  ;; with an alias:
  (is (= (sql/format '{insert-into ((transport t) {select (id, name) from (cars)})})
         ["INSERT INTO transport AS t SELECT id, name FROM cars"]))
  ;; with columns:
  (is (= (sql/format '{insert-into ((transport (id, name)) {select (*) from (cars)})})
         ["INSERT INTO transport (id, name) SELECT * FROM cars"]))
  ;; with an alias and columns:
  (is (= (sql/format '{insert-into (((transport t) (id, name)) {select (*) from (cars)})})
         ["INSERT INTO transport AS t (id, name) SELECT * FROM cars"])))

(deftest issue-293-insert-into-helpers
  ;; and the same set of tests using the helper functions instead:
  (is (= (sql/format (-> (insert-into :transport)
                         (values [[1 "Car"] [2 "Boat"] [3 "Bike"]])))
         ["INSERT INTO transport VALUES (?, ?), (?, ?), (?, ?)" 1 "Car" 2 "Boat" 3 "Bike"]))
  (is (= (sql/format (-> (insert-into :transport)
                         (columns :id :name)
                         (values [[1 "Car"] [2 "Boat"] [3 "Bike"]])))
         ["INSERT INTO transport (id, name) VALUES (?, ?), (?, ?), (?, ?)" 1 "Car" 2 "Boat" 3 "Bike"]))
  ;; with an alias:
  (is (= (sql/format (-> (insert-into :transport :t)
                         (values [[1 "Car"] [2 "Boat"] [3 "Bike"]])))
         ["INSERT INTO transport AS t VALUES (?, ?), (?, ?), (?, ?)" 1 "Car" 2 "Boat" 3 "Bike"]))
  (is (= (sql/format (-> (insert-into :transport :t)
                         (columns :id :name)
                         (values [[1 "Car"] [2 "Boat"] [3 "Bike"]])))
         ["INSERT INTO transport AS t (id, name) VALUES (?, ?), (?, ?), (?, ?)" 1 "Car" 2 "Boat" 3 "Bike"]))
  ;; second case -- table specifier and columns:
  (is (= (sql/format (-> (insert-into :transport [:id :name])
                         (values [[1 "Car"] [2 "Boat"] [3 "Bike"]])))
         ["INSERT INTO transport (id, name) VALUES (?, ?), (?, ?), (?, ?)" 1 "Car" 2 "Boat" 3 "Bike"]))
  ;; with an alias:
  (is (= (sql/format (-> (insert-into [:transport :t] [:id :name])
                         (values [[1 "Car"] [2 "Boat"] [3 "Bike"]])))
         ["INSERT INTO transport AS t (id, name) VALUES (?, ?), (?, ?), (?, ?)" 1 "Car" 2 "Boat" 3 "Bike"]))
  ;; third case -- table/column specifier and query:
  (is (= (sql/format (insert-into :transport '{select (id, name) from (cars)}))
         ["INSERT INTO transport SELECT id, name FROM cars"]))
  ;; with columns:
  (is (= (sql/format (insert-into [:transport [:id :name]] '{select (*) from (cars)}))
         ["INSERT INTO transport (id, name) SELECT * FROM cars"]))
  ;; with an alias:
  (is (= (sql/format (insert-into '(transport t) '{select (id, name) from (cars)}))
         ["INSERT INTO transport AS t SELECT id, name FROM cars"]))
  ;; with columns:
  (is (= (sql/format (insert-into '(transport (id, name)) '{select (*) from (cars)}))
         ["INSERT INTO transport (id, name) SELECT * FROM cars"]))
  ;; with an alias and columns:
  (is (= (sql/format (insert-into ['(transport t) '(id, name)] '{select (*) from (cars)}))
         ["INSERT INTO transport AS t (id, name) SELECT * FROM cars"]))
  ;; three arguments with columns:
  (is (= (sql/format (insert-into :transport [:id :name] '{select (*) from (cars)}))
         ["INSERT INTO transport (id, name) SELECT * FROM cars"]))
  ;; three arguments with an alias and columns:
  (is (= (sql/format (insert-into '(transport t) '(id, name) '{select (*) from (cars)}))
         ["INSERT INTO transport AS t (id, name) SELECT * FROM cars"])))

;; these tests are adapted from Cam Saul's PR #283

(deftest merge-where-no-params-test
  (doseq [[k [f merge-f]] {"WHERE"  [where where]
                           "HAVING" [having having]}]
    (testing "merge-where called with just the map as parameter - see #228"
      (let [sqlmap (-> (select :*)
                       (from :table)
                       (f [:= :foo :bar]))]
        (is (= [(str "SELECT * FROM table " k " foo = bar")]
               (sql/format (apply merge-f sqlmap []))))))))

(deftest merge-where-test
  (doseq [[k sql-keyword f merge-f] [[:where "WHERE" where where]
                                     [:having "HAVING" having having]]]
    (is (= [(str "SELECT * FROM table " sql-keyword " (foo = bar) AND (quuz = xyzzy)")]
           (-> (select :*)
               (from :table)
               (f [:= :foo :bar] [:= :quuz :xyzzy])
               sql/format)))
    (is (= [(str "SELECT * FROM table " sql-keyword " (foo = bar) AND (quuz = xyzzy)")]
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

(deftest merge-where-combine-clauses-test
  (doseq [[k f] {:where  where
                 :having having}]
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

(deftest mysql-on-duplicate-key-update
  (testing "From https://www.mysqltutorial.org/mysql-insert-or-update-on-duplicate-key-update"
    (is (= (sql/format (-> (insert-into :device)
                           (columns :name)
                           (values [["Printer"]])
                           (on-duplicate-key-update {:name "Printer"})))
           ["INSERT INTO device (name) VALUES (?) ON DUPLICATE KEY UPDATE name = ?"
            "Printer" "Printer"]))
    (is (= (sql/format (-> (insert-into :device)
                           (columns :id :name)
                           (values [[4 "Printer"]])
                           (on-duplicate-key-update {:name "Central Printer"})))
           ["INSERT INTO device (id, name) VALUES (?, ?) ON DUPLICATE KEY UPDATE name = ?"
            4 "Printer" "Central Printer"]))
    (is (= (sql/format (-> (insert-into :table)
                           (columns :c1)
                           (values [[42]])
                           (on-duplicate-key-update {:c1 [:+ [:values :c1] 1]})))
           ["INSERT INTO table (c1) VALUES (?) ON DUPLICATE KEY UPDATE c1 = VALUES(c1) + ?"
            42 1]))))

(deftest filter-within-order-by-test
  (testing "PostgreSQL filter, within group, order-by as special syntax"
    (is (= (sql/format {:select [[[:filter :%count.* {:where [:> :i 5]}] :a]
                                 [[:filter ; two pairs -- alias is on last pair
                                   [:avg :x [:order-by :y [:a :desc]]] {:where [:< :i 10]}
                                   [:sum :q] {:where [:= :x nil]}] :b]
                                 [[:within-group [:foo :y] {:order-by [:x]}]]]})
           [(str "SELECT COUNT(*) FILTER (WHERE i > ?) AS a,"
                 " AVG(x, y ORDER BY a DESC) FILTER (WHERE i < ?),"
                 " SUM(q) FILTER (WHERE x IS NULL) AS b,"
                 " FOO(y) WITHIN GROUP (ORDER BY x ASC)")
            5 10])))
  (testing "PostgreSQL filter, within group, order-by as helpers"
    (is (= (sql/format (select [(filter :%count.* (where :> :i 5)) :a]
                               [(filter ; two pairs -- alias is on last pair
                                 ;; order by must remain special syntax here:
                                 [:avg :x [:order-by :y [:a :desc]]] (where :< :i 10)
                                 [:sum :q] (where := :x nil)) :b]
                               [(within-group [:foo :y] (order-by :x))]))
           [(str "SELECT COUNT(*) FILTER (WHERE i > ?) AS a,"
                 " AVG(x, y ORDER BY a DESC) FILTER (WHERE i < ?),"
                 " SUM(q) FILTER (WHERE x IS NULL) AS b,"
                 " FOO(y) WITHIN GROUP (ORDER BY x ASC)")
            5 10]))))

(deftest issue-322
  (testing "Combining WHERE clauses with conditions"
    (is (= {:where [:and [:= :a 1] [:or [:= :b 2] [:= :c 3]]]}
           (where [:= :a 1] [:or [:= :b 2] [:= :c 3]])))
    (is (= (-> (where :or [:= :b 2] [:= :c 3]) ; or first
               (where := :a 1)) ; then implicit and
           (-> (where := :b 2) ; implicit and
               (where :or [:= :c 3]) ; then explicit or
               (where := :a 1)))) ; then implicit and
    (is (= {:where [:and [:or [:= :b 2] [:= :c 3]] [:= :a 1]]}
           (where [:or [:= :b 2] [:= :c 3]] [:= :a 1])
           (-> (where :or [:= :b 2] [:= :c 3]) ; explicit or
               (where := :a 1)))))) ; then implicit and

(deftest issue-324
  (testing "insert-into accepts statement"
    (is (= (-> (with [:a])
               (insert-into [:quux [:x :y]]
                            {:select [:id] :from [:table]}))
           {:with [[:a]],
            :insert-into [[:quux [:x :y]]
                          {:select [:id], :from [:table]}]}))))
