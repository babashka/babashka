;; copyright (c) 2021 sean corfield, all rights reserved

(ns honey.sql-test
  (:refer-clojure :exclude [format])
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [honey.sql :as sut :refer [format]]
            [honey.sql.helpers :as h])
  #?(:clj (:import (clojure.lang ExceptionInfo))))

(deftest mysql-tests
  (is (= ["SELECT * FROM `table` WHERE `id` = ?" 1]
         (sut/format {:select [:*] :from [:table] :where [:= :id 1]}
                     {:dialect :mysql}))))

(deftest expr-tests
  ;; special-cased = nil:
  (is (= ["id IS NULL"]
         (sut/format-expr [:= :id nil])))
  (is (= ["id IS NULL"]
         (sut/format-expr [:is :id nil])))
  (is (= ["id = TRUE"]
         (sut/format-expr [:= :id true])))
  (is (= ["id IS TRUE"]
         (sut/format-expr [:is :id true])))
  (is (= ["id <> TRUE"]
         (sut/format-expr [:<> :id true])))
  (is (= ["id IS NOT TRUE"]
         (sut/format-expr [:is-not :id true])))
  (is (= ["id = FALSE"]
         (sut/format-expr [:= :id false])))
  (is (= ["id IS FALSE"]
         (sut/format-expr [:is :id false])))
  (is (= ["id <> FALSE"]
         (sut/format-expr [:<> :id false])))
  (is (= ["id IS NOT FALSE"]
         (sut/format-expr [:is-not :id false])))
  ;; special-cased <> nil:
  (is (= ["id IS NOT NULL"]
         (sut/format-expr [:<> :id nil])))
  ;; legacy alias:
  (is (= ["id IS NOT NULL"]
         (sut/format-expr [:!= :id nil])))
  ;; legacy alias:
  (is (= ["id IS NOT NULL"]
         (sut/format-expr [:not= :id nil])))
  (is (= ["id IS NOT NULL"]
         (sut/format-expr [:is-not :id nil])))
  ;; degenerate (special) cases:
  (is (= ["NULL IS NULL"]
         (sut/format-expr [:= nil nil])))
  (is (= ["NULL IS NOT NULL"]
         (sut/format-expr [:<> nil nil])))
  (is (= ["id = ?" 1]
         (sut/format-expr [:= :id 1])))
  (is (= ["id + ?" 1]
         (sut/format-expr [:+ :id 1])))
  (is (= ["? + (? + quux)" 1 1]
         (sut/format-expr [:+ 1 [:+ 1 :quux]])))
  (is (= ["? + ? + quux" 1 1]
         (sut/format-expr [:+ 1 1 :quux])))
  (is (= ["FOO(BAR(? + G(abc)), F(?, quux))" 2 1]
         (sut/format-expr [:foo [:bar [:+ 2 [:g :abc]]] [:f 1 :quux]])))
  (is (= ["id"]
         (sut/format-expr :id)))
  (is (= ["?" 1]
         (sut/format-expr 1)))
  (is (= ["INTERVAL ? DAYS" 30]
         (sut/format-expr [:interval 30 :days]))))

(deftest where-test
  (is (= ["WHERE id = ?" 1]
         (#'sut/format-on-expr :where [:= :id 1]))))

(deftest general-tests
  (is (= ["SELECT * FROM \"table\" WHERE \"id\" = ?" 1]
         (sut/format {:select [:*] :from [:table] :where [:= :id 1]} {:quoted true})))
  (is (= ["SELECT \"t\".* FROM \"table\" AS \"t\" WHERE \"id\" = ?" 1]
         (sut/format {:select [:t.*] :from [[:table :t]] :where [:= :id 1]} {:quoted true})))
  (is (= ["SELECT * FROM \"table\" GROUP BY \"foo\", \"bar\""]
         (sut/format {:select [:*] :from [:table] :group-by [:foo :bar]} {:quoted true})))
  (is (= ["SELECT * FROM \"table\" GROUP BY DATE(\"bar\")"]
         (sut/format {:select [:*] :from [:table] :group-by [[:date :bar]]} {:quoted true})))
  (is (= ["SELECT * FROM \"table\" ORDER BY \"foo\" DESC, \"bar\" ASC"]
         (sut/format {:select [:*] :from [:table] :order-by [[:foo :desc] :bar]} {:quoted true})))
  (is (= ["SELECT * FROM \"table\" ORDER BY DATE(\"expiry\") DESC, \"bar\" ASC"]
         (sut/format {:select [:*] :from [:table] :order-by [[[:date :expiry] :desc] :bar]} {:quoted true})))
  (is (= ["SELECT * FROM \"table\" WHERE DATE_ADD(\"expiry\", INTERVAL ? DAYS) < NOW()" 30]
         (sut/format {:select [:*] :from [:table] :where [:< [:date_add :expiry [:interval 30 :days]] [:now]]} {:quoted true})))
  (is (= ["SELECT * FROM `table` WHERE `id` = ?" 1]
         (sut/format {:select [:*] :from [:table] :where [:= :id 1]} {:dialect :mysql})))
  (is (= ["SELECT * FROM \"table\" WHERE \"id\" IN (?, ?, ?, ?)" 1 2 3 4]
         (sut/format {:select [:*] :from [:table] :where [:in :id [1 2 3 4]]} {:quoted true}))))

;; issue-based tests

(deftest subquery-alias-263
  (is (= ["SELECT type FROM (SELECT address AS field_alias FROM Candidate) AS sub_q_alias"]
         (sut/format {:select [:type]
                      :from [[{:select [[:address :field-alias]]
                               :from [:Candidate]} :sub_q_alias]]})))
  (is (= ["SELECT type FROM (SELECT address field_alias FROM Candidate) sub_q_alias"]
         (sut/format {:select [:type]
                      :from [[{:select [[:address :field-alias]]
                               :from [:Candidate]} :sub-q-alias]]}
                     {:dialect :oracle :quoted false}))))

;; tests lifted from HoneySQL 1.x to check for compatibility

(deftest alias-splitting
  (is (= ["SELECT `aa`.`c` AS `a.c`, `bb`.`c` AS `b.c`, `cc`.`c` AS `c.c`"]
         (format {:select [[:aa.c "a.c"]
                           [:bb.c :b.c]
                           [:cc.c 'c.c]]}
                 {:dialect :mysql}))
      "aliases containing \".\" are quoted as necessary but not split"))

(deftest values-alias
  (is (= ["SELECT vals.a FROM (VALUES (?, ?, ?)) AS vals (a, b, c)" 1 2 3]
         (format {:select [:vals.a]
                  :from [[{:values [[1 2 3]]} [:vals {:columns [:a :b :c]}]]]}))))
(deftest test-cte
  (is (= (format {:with [[:query {:select [:foo] :from [:bar]}]]})
         ["WITH query AS (SELECT foo FROM bar)"]))
  (is (= (format {:with [[:query1 {:select [:foo] :from [:bar]}]
                         [:query2 {:select [:bar] :from [:quux]}]]
                  :select [:query1.id :query2.name]
                  :from [:query1 :query2]})
         ["WITH query1 AS (SELECT foo FROM bar), query2 AS (SELECT bar FROM quux) SELECT query1.id, query2.name FROM query1, query2"]))
  (is (= (format {:with-recursive [[:query {:select [:foo] :from [:bar]}]]})
         ["WITH RECURSIVE query AS (SELECT foo FROM bar)"]))
  (is (= (format {:with [[[:static {:columns [:a :b :c]}] {:values [[1 2 3] [4 5]]}]]})
         ["WITH static (a, b, c) AS (VALUES (?, ?, ?), (?, ?, NULL))" 1 2 3 4 5]))
  (is (= (format
           {:with [[[:static {:columns [:a :b :c]}]
                    {:values [[1 2] [4 5 6]]}]]
            :select [:*]
            :from [:static]})
         ["WITH static (a, b, c) AS (VALUES (?, ?, NULL), (?, ?, ?)) SELECT * FROM static" 1 2 4 5 6])))

(deftest insert-into
  (is (= (format {:insert-into :foo})
         ["INSERT INTO foo"]))
  (is (= (format {:insert-into [:foo {:select [:bar] :from [:baz]}]})
         ["INSERT INTO foo SELECT bar FROM baz"]))
  (is (= (format {:insert-into [[:foo [:a :b :c]] {:select [:d :e :f] :from [:baz]}]})
         ["INSERT INTO foo (a, b, c) SELECT d, e, f FROM baz"]))
  (is (= (format {:insert-into [[:foo [:a :b :c]] {:select [:d :e :f] :from [:baz]}]})
         ["INSERT INTO foo (a, b, c) SELECT d, e, f FROM baz"])))

(deftest insert-into-namespaced
  ;; un-namespaced: works as expected:
  (is (= (format {:insert-into :foo :values [{:foo/id 1}]})
         ["INSERT INTO foo (id) VALUES (?)" 1]))
  (is (= (format {:insert-into :foo :columns [:foo/id] :values [[2]]})
         ["INSERT INTO foo (id) VALUES (?)" 2]))
  (is (= (format {:insert-into :foo :values [{:foo/id 1}]}
                 {:namespace-as-table? true})
         ["INSERT INTO foo (id) VALUES (?)" 1]))
  (is (= (format {:insert-into :foo :columns [:foo/id] :values [[2]]}
                 {:namespace-as-table? true})
         ["INSERT INTO foo (id) VALUES (?)" 2])))

(deftest insert-into-uneven-maps
  ;; we can't rely on ordering when the set of keys differs between maps:
  (let [res (format {:insert-into :foo :values [{:id 1} {:id 2, :bar "quux"}]})]
    (is (or (= res ["INSERT INTO foo (id, bar) VALUES (?, NULL), (?, ?)" 1 2 "quux"])
            (= res ["INSERT INTO foo (bar, id) VALUES (NULL, ?), (?, ?)" 1 "quux" 2]))))
  (let [res (format {:insert-into :foo :values [{:id 1, :bar "quux"} {:id 2}]})]
    (is (or (= res ["INSERT INTO foo (id, bar) VALUES (?, ?), (?, NULL)" 1 "quux" 2])
            (= res ["INSERT INTO foo (bar, id) VALUES (?, ?), (NULL, ?)" "quux" 1 2])))))

(deftest exists-test
  ;; EXISTS should never have been implemented as SQL syntax: it's an operator!
  #_(is (= (format {:exists {:select [:a] :from [:foo]}})
           ["EXISTS (SELECT a FROM foo)"]))
  ;; select function call with an alias:
  (is (= (format {:select [[[:exists {:select [:a] :from [:foo]}] :x]]})
         ["SELECT EXISTS (SELECT a FROM foo) AS x"]))
  ;; select function call with no alias required:
  (is (= (format {:select [[[:exists {:select [:a] :from [:foo]}]]]})
         ["SELECT EXISTS (SELECT a FROM foo)"]))
  (is (= (format {:select [:id]
                  :from [:foo]
                  :where [:exists {:select [1]
                                   :from [:bar]
                                   :where :deleted}]})
         ["SELECT id FROM foo WHERE EXISTS (SELECT ? FROM bar WHERE deleted)" 1])))

(deftest array-test
  (is (= (format {:insert-into :foo
                  :columns [:baz]
                  :values [[[:array [1 2 3 4]]]]})
         ["INSERT INTO foo (baz) VALUES (ARRAY[?, ?, ?, ?])" 1 2 3 4]))
  (is (= (format {:insert-into :foo
                  :columns [:baz]
                  :values [[[:array ["one" "two" "three"]]]]})
         ["INSERT INTO foo (baz) VALUES (ARRAY[?, ?, ?])" "one" "two" "three"])))

(deftest union-test
  ;; UNION and INTERSECT subexpressions should not be parenthesized.
  ;; If you need to add more complex expressions, use a subquery like this:
  ;;   SELECT foo FROM bar1
  ;;   UNION
  ;;   SELECT foo FROM (SELECT foo FROM bar2 ORDER BY baz LIMIT 2)
  ;;   ORDER BY foo ASC
  (is (= (format {:union [{:select [:foo] :from [:bar1]}
                          {:select [:foo] :from [:bar2]}]})
         ["SELECT foo FROM bar1 UNION SELECT foo FROM bar2"]))

  (testing "union complex values"
    (is (= (format {:union [{:select [:foo] :from [:bar1]}
                            {:select [:foo] :from [:bar2]}]
                    :with [[[:bar {:columns [:spam :eggs]}]
                            {:values [[1 2] [3 4] [5 6]]}]]})
           ["WITH bar (spam, eggs) AS (VALUES (?, ?), (?, ?), (?, ?)) SELECT foo FROM bar1 UNION SELECT foo FROM bar2"
            1 2 3 4 5 6]))))

(deftest union-all-test
  (is (= (format {:union-all [{:select [:foo] :from [:bar1]}
                              {:select [:foo] :from [:bar2]}]})
         ["SELECT foo FROM bar1 UNION ALL SELECT foo FROM bar2"])))

(deftest intersect-test
  (is (= (format {:intersect [{:select [:foo] :from [:bar1]}
                              {:select [:foo] :from [:bar2]}]})
         ["SELECT foo FROM bar1 INTERSECT SELECT foo FROM bar2"])))

(deftest except-test
  (is (= (format {:except [{:select [:foo] :from [:bar1]}
                           {:select [:foo] :from [:bar2]}]})
         ["SELECT foo FROM bar1 EXCEPT SELECT foo FROM bar2"])))

(deftest inner-parts-test
  (testing "The correct way to apply ORDER BY to various parts of a UNION"
    (is (= (format
             {:union
              [{:select [:amount :id :created_on]
                :from [:transactions]}
               {:select [:amount :id :created_on]
                :from [{:select [:amount :id :created_on]
                        :from [:other_transactions]
                        :order-by [[:amount :desc]]
                        :limit 5}]}]
              :order-by [[:amount :asc]]})
           ["SELECT amount, id, created_on FROM transactions UNION SELECT amount, id, created_on FROM (SELECT amount, id, created_on FROM other_transactions ORDER BY amount DESC LIMIT ?) ORDER BY amount ASC" 5]))))

(deftest compare-expressions-test
  (testing "Sequences should be fns when in value/comparison spots"
    (is (= ["SELECT foo FROM bar WHERE (col1 MOD ?) = (col2 + ?)" 4 4]
           (format {:select [:foo]
                    :from [:bar]
                    :where [:= [:mod :col1 4] [:+ :col2 4]]}))))

  (testing "Example from dharrigan"
    (is (= ["SELECT PG_TRY_ADVISORY_LOCK(1)"]
           (format {:select [:%pg_try_advisory_lock.1]}))))

  (testing "Value context only applies to sequences in value/comparison spots"
    (let [sub {:select [:%sum.amount]
               :from [:bar]
               :where [:in :id ["id-1" "id-2"]]}]
      (is (= ["SELECT total FROM foo WHERE (SELECT SUM(amount) FROM bar WHERE id IN (?, ?)) = total" "id-1" "id-2"]
             (format {:select [:total]
                      :from [:foo]
                      :where [:= sub :total]})))
      (is (= ["WITH t AS (SELECT SUM(amount) FROM bar WHERE id IN (?, ?)) SELECT total FROM foo WHERE total = t" "id-1" "id-2"]
             (format {:with [[:t sub]]
                      :select [:total]
                      :from [:foo]
                      :where [:= :total :t]}))))))

(deftest union-with-cte
  (is (= (format {:union [{:select [:foo] :from [:bar1]}
                          {:select [:foo] :from [:bar2]}]
                  :with [[[:bar {:columns [:spam :eggs]}]
                          {:values [[1 2] [3 4] [5 6]]}]]})
         ["WITH bar (spam, eggs) AS (VALUES (?, ?), (?, ?), (?, ?)) SELECT foo FROM bar1 UNION SELECT foo FROM bar2" 1 2 3 4 5 6])))

(deftest union-all-with-cte
  (is (= (format {:union-all [{:select [:foo] :from [:bar1]}
                              {:select [:foo] :from [:bar2]}]
                  :with [[[:bar {:columns [:spam :eggs]}]
                          {:values [[1 2] [3 4] [5 6]]}]]})
         ["WITH bar (spam, eggs) AS (VALUES (?, ?), (?, ?), (?, ?)) SELECT foo FROM bar1 UNION ALL SELECT foo FROM bar2" 1 2 3 4 5 6])))

(deftest parameterizer-none
  (testing "array parameter"
    (is (= (format {:insert-into :foo
                    :columns [:baz]
                    :values [[[:array [1 2 3 4]]]]}
                   {:inline true})
           ["INSERT INTO foo (baz) VALUES (ARRAY[1, 2, 3, 4])"])))

  (testing "union complex values -- fail: parameterizer"
    (is (= (format {:union [{:select [:foo] :from [:bar1]}
                            {:select [:foo] :from [:bar2]}]
                    :with [[[:bar {:columns [:spam :eggs]}]
                            {:values [[1 2] [3 4] [5 6]]}]]}
                   {:inline true})
           ["WITH bar (spam, eggs) AS (VALUES (1, 2), (3, 4), (5, 6)) SELECT foo FROM bar1 UNION SELECT foo FROM bar2"]))))

(deftest inline-was-parameterizer-none
  (testing "array parameter"
    (is (= (format {:insert-into :foo
                    :columns [:baz]
                    :values [[[:array (mapv vector
                                            (repeat :inline)
                                            [1 2 3 4])]]]})
           ["INSERT INTO foo (baz) VALUES (ARRAY[1, 2, 3, 4])"])))

  (testing "union complex values"
    (is (= (format {:union [{:select [:foo] :from [:bar1]}
                            {:select [:foo] :from [:bar2]}]
                    :with [[[:bar {:columns [:spam :eggs]}]
                            {:values (mapv #(mapv vector (repeat :inline) %)
                                           [[1 2] [3 4] [5 6]])}]]})
           ["WITH bar (spam, eggs) AS (VALUES (1, 2), (3, 4), (5, 6)) SELECT foo FROM bar1 UNION SELECT foo FROM bar2"]))))

(deftest similar-regex-tests
  (testing "basic similar to"
    (is (= (format {:select :* :from :foo
                    :where [:similar-to :foo [:escape "bar" [:inline  "*"]]]})
           ["SELECT * FROM foo WHERE foo SIMILAR TO ? ESCAPE '*'" "bar"]))))

(deftest former-parameterizer-tests-where-and
  ;; I have no plans for positional parameters -- I just don't see the point
  #_(testing "should ignore a nil predicate -- fail: postgresql parameterizer"
      (is (= (format {:where [:and
                              [:= :foo "foo"]
                              [:= :bar "bar"]
                              nil
                              [:= :quux "quux"]]}
                     {:parameterizer :postgresql})
             ["WHERE (foo = ?) AND (bar = $2) AND (quux = $3)" "foo" "bar" "quux"])))
  ;; new :inline option is similar to :parameterizer :none in 1.x
  (testing "should fill param with single quote"
    (is (= (format {:where [:and
                            [:= :foo "foo"]
                            [:= :bar "bar"]
                            nil
                            [:= :quux "quux"]]}
                   {:inline true})
           ["WHERE (foo = 'foo') AND (bar = 'bar') AND (quux = 'quux')"])))
  (testing "should inline params with single quote"
    (is (= (format {:where [:and
                            [:= :foo [:inline "foo"]]
                            [:= :bar [:inline "bar"]]
                            nil
                            [:= :quux [:inline "quux"]]]})
           ["WHERE (foo = 'foo') AND (bar = 'bar') AND (quux = 'quux')"])))
  ;; this is the normal behavior -- not a custom parameterizer!
  (testing "should fill param with ?"
    (is (= (format {:where [:and
                            [:= :foo "foo"]
                            [:= :bar "bar"]
                            nil
                            [:= :quux "quux"]]}
                   ;; this never did anything useful:
                   #_{:parameterizer :mysql-fill})
           ["WHERE (foo = ?) AND (bar = ?) AND (quux = ?)" "foo" "bar" "quux"]))))

(deftest set-before-from
  ;; issue 235
  (is (=
       ["UPDATE \"films\" \"f\" SET \"kind\" = \"c\".\"test\" FROM (SELECT \"b\".\"test\" FROM \"bar\" AS \"b\" WHERE \"b\".\"id\" = ?) AS \"c\" WHERE \"f\".\"kind\" = ?" 1 "drama"]
       (->
        {:update [:films :f]
         :set    {:kind :c.test}
         :from   [[{:select [:b.test]
                    :from   [[:bar :b]]
                    :where  [:= :b.id 1]} :c]]
         :where  [:= :f.kind "drama"]}
        (format {:quoted true}))))
  ;; issue 317
  (is (=
       ["UPDATE \"films\" \"f\" SET \"kind\" = \"c\".\"test\" FROM (SELECT \"b\".\"test\" FROM \"bar\" AS \"b\" WHERE \"b\".\"id\" = ?) AS \"c\" WHERE \"f\".\"kind\" = ?" 1 "drama"]
       (->
        {:update [:films :f]
         ;; drop ns in set clause...
         :set    {:f/kind :c.test}
         :from   [[{:select [:b.test]
                    :from   [[:bar :b]]
                    :where  [:= :b.id 1]} :c]]
         :where  [:= :f.kind "drama"]}
        (format {:quoted true}))))
  (is (=
       ["UPDATE \"films\" \"f\" SET \"f\".\"kind\" = \"c\".\"test\" FROM (SELECT \"b\".\"test\" FROM \"bar\" AS \"b\" WHERE \"b\".\"id\" = ?) AS \"c\" WHERE \"f\".\"kind\" = ?" 1 "drama"]
       (->
        {:update [:films :f]
         ;; ...but keep literal dotted name
         :set    {:f.kind :c.test}
         :from   [[{:select [:b.test]
                    :from   [[:bar :b]]
                    :where  [:= :b.id 1]} :c]]
         :where  [:= :f.kind "drama"]}
        (format {:quoted true})))))

(deftest set-after-join
  (is (=
       ["UPDATE `foo` INNER JOIN `bar` ON `bar`.`id` = `foo`.`bar_id` SET `a` = ? WHERE `bar`.`b` = ?" 1 42]
       (->
        {:update :foo
         :join   [:bar [:= :bar.id :foo.bar_id]]
         :set    {:a 1}
         :where  [:= :bar.b 42]}
        (format {:dialect :mysql}))))
  ;; issue 344
  (is (=
       ["UPDATE `foo` INNER JOIN `bar` ON `bar`.`id` = `foo`.`bar_id` SET `f`.`a` = ? WHERE `bar`.`b` = ?" 1 42]
       (->
        {:update :foo
         :join   [:bar [:= :bar.id :foo.bar_id]]
         ;; do not drop ns in set clause for MySQL:
         :set    {:f/a 1}
         :where  [:= :bar.b 42]}
        (format {:dialect :mysql})))))

(deftest format-arity-test
  (testing "format can be called with no options"
    (is (= ["DELETE FROM foo WHERE foo.id = ?" 42]
           (-> {:delete-from :foo
                :where [:= :foo.id 42]}
               (format)))))
  (testing "format can be called with an options hash map"
    (is (= ["\nDELETE FROM `foo`\nWHERE `foo`.`id` = ?\n" 42]
           (-> {:delete-from :foo
                :where [:= :foo.id 42]}
               (format {:dialect :mysql :pretty true})))))
  (testing "format can be called with named arguments"
    (is (= ["\nDELETE FROM `foo`\nWHERE `foo`.`id` = ?\n" 42]
           (-> {:delete-from :foo
                :where [:= :foo.id 42]}
               (format :dialect :mysql :pretty true)))))
  ;; BB-TEST-PATCH: bb doesn't have clojure-version
  (when (str/starts-with? #?(:bb  "1.11"
                             :clj (clojure-version)
                             :cljs *clojurescript-version*) "1.11")
    (testing "format can be called with mixed arguments"
      (is (= ["\nDELETE FROM `foo`\nWHERE `foo`.`id` = ?\n" 42]
             (-> {:delete-from :foo
                  :where [:= :foo.id 42]}
                 (format :dialect :mysql {:pretty true})))))))

(deftest delete-from-test
  (is (= ["DELETE FROM `foo` WHERE `foo`.`id` = ?" 42]
         (-> {:delete-from :foo
              :where [:= :foo.id 42]}
             (format {:dialect :mysql})))))

(deftest delete-test
  (is (= ["DELETE `t1`, `t2` FROM `table1` AS `t1` INNER JOIN `table2` AS `t2` ON `t1`.`fk` = `t2`.`id` WHERE `t1`.`bar` = ?" 42]
         (-> {:delete [:t1 :t2]
              :from [[:table1 :t1]]
              :join [[:table2 :t2] [:= :t1.fk :t2.id]]
              :where [:= :t1.bar 42]}
             (format {:dialect :mysql})))))

(deftest delete-using
  (is (= ["DELETE FROM films USING producers WHERE (producer_id = producers.id) AND (producers.name = ?)" "foo"]
         (-> {:delete-from :films
              :using [:producers]
              :where [:and
                      [:= :producer_id :producers.id]
                      [:= :producers.name "foo"]]}
             (format)))))

(deftest truncate-test
  (is (= ["TRUNCATE `foo`"]
         (-> {:truncate :foo}
             (format {:dialect :mysql})))))

(deftest inlined-values-are-stringified-correctly
  (is (= ["SELECT 'foo', 'It''s a quote!', bar, NULL"]
         (format {:select [[[:inline "foo"]]
                           [[:inline "It's a quote!"]]
                           [[:inline :bar]]
                           [[:inline nil]]]}))))

;; Make sure if Locale is Turkish we're not generating queries like İNNER JOIN (dot over the I) because
;; `string/upper-case` is converting things to upper-case using the default Locale. Generated query should be the same
;; regardless of system Locale. See #236
#?(:clj
   (deftest statements-generated-correctly-with-turkish-locale
     (let [format-with-locale (fn [^String language-tag]
                                (let [original-locale (java.util.Locale/getDefault)]
                                  (try
                                    (java.util.Locale/setDefault (java.util.Locale/forLanguageTag language-tag))
                                    (format {:select [:t2.name]
                                             :from   [[:table1 :t1]]
                                             :join   [[:table2 :t2] [:= :t1.fk :t2.id]]
                                             :where  [:= :t1.id 1]})
                                    (finally
                                      (java.util.Locale/setDefault original-locale)))))]
       (is (= (format-with-locale "en")
              (format-with-locale "tr"))))))

(deftest join-on-true-253
  ;; used to work on honeysql 0.9.2; broke in 0.9.3
  (is (= ["SELECT foo FROM bar INNER JOIN table AS t ON TRUE"]
         (format {:select [:foo]
                  :from [:bar]
                  :join [[:table :t] true]}))))

(deftest cross-join-test
  (is (= ["SELECT * FROM foo CROSS JOIN bar"]
         (format {:select [:*]
                  :from [:foo]
                  :cross-join [:bar]})))
  (is (= ["SELECT * FROM foo AS f CROSS JOIN bar b"]
         (format {:select [:*]
                  :from [[:foo :f]]
                  :cross-join [[:bar :b]]}))))

(deftest locking-select-tests
  (testing "PostgreSQL/ANSI FOR"
    (is (= ["SELECT * FROM foo FOR UPDATE"]
           (format {:select [:*] :from :foo :for :update})))
    (is (= ["SELECT * FROM foo FOR NO KEY UPDATE"]
           (format {:select [:*] :from :foo :for :no-key-update})))
    (is (= ["SELECT * FROM foo FOR SHARE"]
           (format {:select [:*] :from :foo :for :share})))
    (is (= ["SELECT * FROM foo FOR KEY SHARE"]
           (format {:select [:*] :from :foo :for :key-share})))
    (is (= ["SELECT * FROM foo FOR UPDATE"]
           (format {:select [:*] :from :foo :for [:update]})))
    (is (= ["SELECT * FROM foo FOR NO KEY UPDATE"]
           (format {:select [:*] :from :foo :for [:no-key-update]})))
    (is (= ["SELECT * FROM foo FOR SHARE"]
           (format {:select [:*] :from :foo :for [:share]})))
    (is (= ["SELECT * FROM foo FOR KEY SHARE"]
           (format {:select [:*] :from :foo :for [:key-share]})))
    (is (= ["SELECT * FROM foo FOR UPDATE NOWAIT"]
           (format {:select [:*] :from :foo :for [:update :nowait]})))
    (is (= ["SELECT * FROM foo FOR UPDATE OF bar NOWAIT"]
           (format {:select [:*] :from :foo :for [:update :bar :nowait]})))
    (is (= ["SELECT * FROM foo FOR UPDATE WAIT"]
           (format {:select [:*] :from :foo :for [:update :wait]})))
    (is (= ["SELECT * FROM foo FOR UPDATE OF bar WAIT"]
           (format {:select [:*] :from :foo :for [:update :bar :wait]})))
    (is (= ["SELECT * FROM foo FOR UPDATE SKIP LOCKED"]
           (format {:select [:*] :from :foo :for [:update :skip-locked]})))
    (is (= ["SELECT * FROM foo FOR UPDATE OF bar SKIP LOCKED"]
           (format {:select [:*] :from :foo :for [:update :bar :skip-locked]})))
    (is (= ["SELECT * FROM foo FOR UPDATE OF bar, quux"]
           (format {:select [:*] :from :foo :for [:update [:bar :quux]]}))))
  (testing "MySQL for/lock"
    ;; these examples come from:
    (is (= ["SELECT * FROM t1 WHERE c1 = (SELECT c1 FROM t2) FOR UPDATE"] ; portable
           (format {:select [:*] :from :t1
                    :where [:= :c1 {:select [:c1] :from :t2}]
                    :for [:update]})))
    (is (= ["SELECT * FROM t1 WHERE c1 = (SELECT c1 FROM t2 FOR UPDATE) FOR UPDATE"]
           (format {:select [:*] :from :t1
                    :where [:= :c1 {:select [:c1] :from :t2 :for [:update]}]
                    :for [:update]})))
    (is (= ["SELECT * FROM foo WHERE name = 'Jones' LOCK IN SHARE MODE"] ; MySQL-specific
           (format {:select [:*] :from :foo
                    :where [:= :name [:inline "Jones"]]
                    :lock [:in-share-mode]}
                   {:dialect :mysql :quoted false})))))

(deftest insert-example-tests
  ;; these examples are taken from https://www.postgresql.org/docs/13/sql-insert.html
  (is (= ["
INSERT INTO films
VALUES ('UA502', 'Bananas', 105, '1971-07-13', 'Comedy', '82 minutes')
"]
         (format {:insert-into :films
                  :values [[[:inline "UA502"] [:inline "Bananas"] [:inline 105]
                            [:inline "1971-07-13"] [:inline "Comedy"]
                            [:inline "82 minutes"]]]}
                 {:pretty true})))
  (is (= ["
INSERT INTO films
VALUES (?, ?, ?, ?, ?, ?)
" "UA502", "Bananas", 105, "1971-07-13", "Comedy", "82 minutes"]
         (format {:insert-into :films
                  :values [["UA502" "Bananas" 105 "1971-07-13" "Comedy" "82 minutes"]]}
                 {:pretty true})))
  (is (= ["
INSERT INTO films
(code, title, did, date_prod, kind)
VALUES (?, ?, ?, ?, ?)
" "T_601", "Yojimo", 106, "1961-06-16", "Drama"]
         (format {:insert-into :films
                  :columns [:code :title :did :date_prod :kind]
                  :values [["T_601", "Yojimo", 106, "1961-06-16", "Drama"]]}
                 {:pretty true})))
  (is (= ["
INSERT INTO films
VALUES (?, ?, ?, DEFAULT, ?, ?)
" "UA502", "Bananas", 105, "Comedy", "82 minutes"]
         (format {:insert-into :films
                  :values [["UA502" "Bananas" 105 [:default] "Comedy" "82 minutes"]]}
                 {:pretty true})))
  (is (= ["
INSERT INTO films
(code, title, did, date_prod, kind)
VALUES (?, ?, ?, DEFAULT, ?)
" "T_601", "Yojimo", 106, "Drama"]
         (format {:insert-into :films
                  :columns [:code :title :did :date_prod :kind]
                  :values [["T_601", "Yojimo", 106, [:default], "Drama"]]}
                 {:pretty true}))))

(deftest on-conflict-tests
  ;; these examples are taken from https://www.postgresqltutorial.com/postgresql-upsert/
  (is (= ["
INSERT INTO customers
(name, email)
VALUES ('Microsoft', 'hotline@microsoft.com')
ON CONFLICT ON CONSTRAINT customers_name_key
DO NOTHING
"]
         (format {:insert-into :customers
                  :columns [:name :email]
                  :values [[[:inline "Microsoft"], [:inline "hotline@microsoft.com"]]]
                  :on-conflict {:on-constraint :customers_name_key}
                  :do-nothing true}
                 {:pretty true})))
  (is (= ["
INSERT INTO customers
(name, email)
VALUES ('Microsoft', 'hotline@microsoft.com')
ON CONFLICT
ON CONSTRAINT customers_name_key
DO NOTHING
"]
         (format {:insert-into :customers
                  :columns [:name :email]
                  :values [[[:inline "Microsoft"], [:inline "hotline@microsoft.com"]]]
                  :on-conflict []
                  :on-constraint :customers_name_key
                  :do-nothing true}
                 {:pretty true})))
  (is (= ["
INSERT INTO customers
(name, email)
VALUES ('Microsoft', 'hotline@microsoft.com')
ON CONFLICT (name)
DO NOTHING
"]
         (format {:insert-into :customers
                  :columns [:name :email]
                  :values [[[:inline "Microsoft"], [:inline "hotline@microsoft.com"]]]
                  :on-conflict :name
                  :do-nothing true}
                 {:pretty true})))
  (is (= ["
INSERT INTO customers
(name, email)
VALUES ('Microsoft', 'hotline@microsoft.com')
ON CONFLICT (name)
DO NOTHING
"]
         (format {:insert-into :customers
                  :columns [:name :email]
                  :values [[[:inline "Microsoft"], [:inline "hotline@microsoft.com"]]]
                  :on-conflict [:name]
                  :do-nothing true}
                 {:pretty true})))
  (is (= ["
INSERT INTO customers
(name, email)
VALUES ('Microsoft', 'hotline@microsoft.com')
ON CONFLICT (name, email)
DO NOTHING
"]
         (format {:insert-into :customers
                  :columns [:name :email]
                  :values [[[:inline "Microsoft"], [:inline "hotline@microsoft.com"]]]
                  :on-conflict [:name :email]
                  :do-nothing true}
                 {:pretty true})))
  (is (= ["
INSERT INTO customers
(name, email)
VALUES ('Microsoft', 'hotline@microsoft.com')
ON CONFLICT (name)
DO UPDATE SET email = EXCLUDED.email || ';' || customers.email
"]
         (format {:insert-into :customers
                  :columns [:name :email]
                  :values [[[:inline "Microsoft"], [:inline "hotline@microsoft.com"]]]
                  :on-conflict :name
                  :do-update-set {:email [:|| :EXCLUDED.email [:inline ";"] :customers.email]}}
                 {:pretty true}))))

(deftest issue-285
  (is (= ["
SELECT *
FROM processes
WHERE state = ?
ORDER BY id = ? DESC
" 42 123]
         (format (-> (h/select :*)
                     (h/from :processes)
                     (h/where [:= :state 42])
                     (h/order-by [[:= :id 123] :desc]))
                 {:pretty true}))))

(deftest issue-299-test
  (let [name    "test field"
        ;; this was a bug in 1.x -- adding here to prevent regression:
        enabled [true, "); SELECT case when (SELECT current_setting('is_superuser'))='off' then pg_sleep(0.2) end; -- "]]
    (is (= ["INSERT INTO table (name, enabled) VALUES (?, (TRUE, ?))" name (second enabled)]
           (format {:insert-into :table
                    :values [{:name name
                              :enabled enabled}]})))))

(deftest issue-316-test
  (testing "SQL injection via keyword is detected"
    (let [sort-column "foo; select * from users"]
      (try
        (-> {:select [:foo :bar]
             :from [:mytable]
             :order-by [(keyword sort-column)]}
            (format))
        (is false "; not detected in entity!")
        (catch #?(:clj Throwable :cljs :default) e
          (is (:disallowed (ex-data e))))))))
    ;; should not produce: ["SELECT foo, bar FROM mytable ORDER BY foo; select * from users"]


(deftest issue-319-test
  (testing "that registering a clause is idempotent"
    (is (= ["FOO"]
           (do
             (sut/register-clause! :foo (constantly ["FOO"]) nil)
             (sut/register-clause! :foo (constantly ["FOO"]) nil)
             (format {:foo []}))))))

(deftest issue-321-linting
  (testing "empty IN is ignored by default"
    (is (= ["WHERE x IN ()"]
           (format {:where [:in :x []]})))
    (is (= ["WHERE x IN ()"]
           (format {:where [:in :x :?y]}
                   {:params {:y []}}))))
  (testing "empty IN is flagged in basic mode"
    (is (thrown-with-msg? ExceptionInfo #"empty collection"
                          (format {:where [:in :x []]}
                                  {:checking :basic})))
    (is (thrown-with-msg? ExceptionInfo #"empty collection"
                          (format {:where [:in :x :?y]}
                                  {:params {:y []} :checking :basic}))))
  (testing "IN NULL is ignored by default and basic"
    (is (= ["WHERE x IN (NULL)"]
           (format {:where [:in :x [nil]]})))
    (is (= ["WHERE x IN (NULL)"]
           (format {:where [:in :x [nil]]}
                   {:checking :basic})))
    (is (= ["WHERE x IN (?)" nil]
           (format {:where [:in :x :?y]}
                   {:params {:y [nil]}})))
    (is (= ["WHERE x IN (?)" nil]
           (format {:where [:in :x :?y]}
                   {:params {:y [nil]} :checking :basic}))))
  (testing "IN NULL is flagged in strict mode"
    (is (thrown-with-msg? ExceptionInfo #"does not match"
                          (format {:where [:in :x [nil]]}
                                  {:checking :strict})))
    (is (thrown-with-msg? ExceptionInfo #"does not match"
                          (format {:where [:in :x :?y]}
                                  {:params {:y [nil]} :checking :strict})))))

(deftest quoting-:%-syntax
  (testing "quoting of expressions in functions shouldn't depend on syntax"
    (is (= ["SELECT SYSDATE()"]
           (format {:select [[[:sysdate]]]})
           (format {:select :%sysdate})))
    (is (= ["SELECT COUNT(*)"]
           (format {:select [[[:count :*]]]})
           (format {:select :%count.*})))
    (is (= ["SELECT AVERAGE(`foo-foo`)"]
           (format {:select [[[:average :foo-foo]]]} :dialect :mysql)
           (format {:select :%average.foo-foo} :dialect :mysql)))
    (is (= ["SELECT GREATER(`foo-foo`, `bar-bar`)"]
           (format {:select [[[:greater :foo-foo :bar-bar]]]} :dialect :mysql)
           (format {:select :%greater.foo-foo.bar-bar} :dialect :mysql)))
    (is (= ["SELECT MIXED_KEBAB(`yum-yum`)"]
           (format {:select :%mixed-kebab.yum-yum} :dialect :mysql)))
    (is (= ["SELECT MIXED_KEBAB(`yum_yum`)"]
           (format {:select :%mixed-kebab.yum-yum} :dialect :mysql :quoted-snake true)))
    ;; qualifier is always - -> _ converted:
    (is (= ["SELECT MIXED_KEBAB(`yum_yum`.`bar-bar`, `a_b`.`c-d`)"]
           (format {:select (keyword "%mixed-kebab.yum-yum/bar-bar.a-b/c-d")} :dialect :mysql)))
    ;; name is only - -> _ converted when snake_case requested:
    (is (= ["SELECT MIXED_KEBAB(`yum_yum`.`bar_bar`, `a_b`.`c_d`)"]
           (format {:select (keyword "%mixed-kebab.yum-yum/bar-bar.a-b/c-d")} :dialect :mysql :quoted-snake true)))
    (is (= ["SELECT RANSOM(`NoTe`)"]
           (format {:select [[[:ransom :NoTe]]]} :dialect :mysql)
           (format {:select :%ransom.NoTe} :dialect :mysql)))))

(deftest join-without-on-using
  ;; essentially issue 326
  (testing "join does not need on or using"
    (is (= ["SELECT foo FROM bar INNER JOIN quux"]
           (format {:select :foo
                    :from :bar
                    :join [:quux]}))))
  (testing "join on select with parameters"
    (is (= ["SELECT foo FROM bar INNER JOIN (SELECT a FROM b WHERE id = ?) WHERE id = ?" 123 456]
           (format {:select :foo
                    :from :bar
                    :join [{:select :a :from :b :where [:= :id 123]}]
                    :where [:= :id 456]})))
    (is (= ["SELECT foo FROM bar INNER JOIN (SELECT a FROM b WHERE id = ?) AS x WHERE id = ?" 123 456]
           (format {:select :foo
                    :from :bar
                    :join [[{:select :a :from :b :where [:= :id 123]} :x]]
                    :where [:= :id 456]})))
    (is (= ["SELECT foo FROM bar INNER JOIN (SELECT a FROM b WHERE id = ?) AS x ON y WHERE id = ?" 123 456]
           (format {:select :foo
                    :from :bar
                    :join [[{:select :a :from :b :where [:= :id 123]} :x] :y]
                    :where [:= :id 456]})))))

(deftest fetch-offset-issue-338
  (testing "default offset (with and without limit)"
    (is (= ["SELECT foo FROM bar LIMIT ? OFFSET ?" 10 20]
           (format {:select :foo :from :bar
                    :limit 10 :offset 20})))
    (is (= ["SELECT foo FROM bar OFFSET ?" 20]
           (format {:select :foo :from :bar
                    :offset 20}))))
  (testing "default offset / fetch"
    (is (= ["SELECT foo FROM bar OFFSET ? ROWS FETCH NEXT ? ROWS ONLY" 20 10]
           (format {:select :foo :from :bar
                    :fetch 10 :offset 20})))
    (is (= ["SELECT foo FROM bar OFFSET ? ROW FETCH NEXT ? ROW ONLY" 1 1]
           (format {:select :foo :from :bar
                    :fetch 1 :offset 1})))
    (is (= ["SELECT foo FROM bar FETCH FIRST ? ROWS ONLY" 2]
           (format {:select :foo :from :bar
                    :fetch 2}))))
  (testing "SQL Server offset"
    (is (= ["SELECT [foo] FROM [bar] OFFSET ? ROWS FETCH NEXT ? ROWS ONLY" 20 10]
           (format {:select :foo :from :bar
                    :fetch 10 :offset 20}
                   {:dialect :sqlserver})))
    (is (= ["SELECT [foo] FROM [bar] OFFSET ? ROWS" 20]
           (format {:select :foo :from :bar
                    :offset 20}
                   {:dialect :sqlserver})))))
