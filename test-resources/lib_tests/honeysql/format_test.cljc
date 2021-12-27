(ns honeysql.format-test
  (:refer-clojure :exclude [format])
  (:require [#?@(:clj [clojure.test :refer]
                 :cljs [cljs.test :refer-macros]) [deftest testing is are]]
            honeysql.core
            [honeysql.types :as sql]
            [honeysql.format :refer
             [*allow-dashed-names?* *allow-namespaced-names?*
              *namespace-as-table?*
              quote-identifier format-clause format
              parameterize]]))

(deftest test-quote
  (are
    [qx res]
    (= (apply quote-identifier "foo.bar.baz" qx) res)
    [] "foo.bar.baz"
    [:style :mysql] "`foo`.`bar`.`baz`"
    [:style :mysql :split false] "`foo.bar.baz`")
  (are
    [x res]
    (= (quote-identifier x) res)
    3 "3"
    'foo "foo"
    :foo-bar "foo_bar")
  (is (= (quote-identifier "*" :style :ansi) "*"))
  (is (= (quote-identifier "foo\"bar" :style :ansi) "\"foo\"\"bar\""))
  (is (= (quote-identifier "foo\"bar" :style :oracle) "\"foo\"\"bar\""))
  (is (= (quote-identifier "foo`bar" :style :mysql) "`foo``bar`"))
  (is (= (quote-identifier "foo]bar" :style :sqlserver) "[foo]]bar]")))

(deftest test-dashed-quote
  (binding [*allow-dashed-names?* true]
    (is (= (quote-identifier :foo-bar) "foo-bar"))
    (is (= (quote-identifier :foo-bar :style :ansi) "\"foo-bar\""))
    (is (= (quote-identifier :foo-bar.moo-bar :style :ansi)
           "\"foo-bar\".\"moo-bar\""))))

(deftest test-namespaced-identifier
  (is (= (quote-identifier :foo/bar) "bar"))
  (is (= (quote-identifier :foo/bar :style :ansi) "\"bar\""))
  (binding [*namespace-as-table?* true]
    (is (= (quote-identifier :foo/bar) "foo.bar"))
    (is (= (quote-identifier :foo/bar :style :ansi) "\"foo\".\"bar\""))
    (is (= (quote-identifier :foo/bar :style :ansi :split false) "\"foo.bar\"")))
  (binding [*allow-namespaced-names?* true]
    (is (= (quote-identifier :foo/bar) "foo/bar"))
    (is (= (quote-identifier :foo/bar :style :ansi) "\"foo/bar\""))))

(deftest alias-splitting
  (is (= ["SELECT `aa`.`c` AS `a.c`, `bb`.`c` AS `b.c`, `cc`.`c` AS `c.c`"]
         (format {:select [[:aa.c "a.c"]
                           [:bb.c :b.c]
                           [:cc.c 'c.c]]}
                 :quoting :mysql))
      "aliases containing \".\" are quoted as necessary but not split"))

(deftest values-alias
  (is (= ["SELECT vals.a FROM (VALUES (?, ?, ?)) vals (a, b, c)" 1 2 3]
         (format {:select [:vals.a]
                  :from [[{:values [[1 2 3]]} [:vals {:columns [:a :b :c]}]]]}))))
(deftest test-cte
  (is (= (format-clause
          (first {:with [[:query {:select [:foo] :from [:bar]}]]}) nil)
         "WITH query AS SELECT foo FROM bar"))
  (is (= (format-clause
          (first {:with-recursive [[:query {:select [:foo] :from [:bar]}]]}) nil)
         "WITH RECURSIVE query AS SELECT foo FROM bar"))
  (is (= (format {:with [[[:static {:columns [:a :b :c]}] {:values [[1 2 3] [4 5 6]]}]]})
         ["WITH static (a, b, c) AS (VALUES (?, ?, ?), (?, ?, ?))" 1 2 3 4 5 6]))
  (is (= (format
           {:with [[[:static {:columns [:a :b :c]}]
                    {:values [[1 2 3] [4 5 6]]}]]
            :select [:*]
            :from [:static]})
         ["WITH static (a, b, c) AS (VALUES (?, ?, ?), (?, ?, ?)) SELECT * FROM static" 1 2 3 4 5 6])))

(deftest insert-into
  (is (= (format-clause (first {:insert-into :foo}) nil)
         "INSERT INTO foo"))
  (is (= (format-clause (first {:insert-into [:foo {:select [:bar] :from [:baz]}]}) nil)
         "INSERT INTO foo SELECT bar FROM baz"))
  (is (= (format-clause (first {:insert-into [[:foo [:a :b :c]] {:select [:d :e :f] :from [:baz]}]}) nil)
         "INSERT INTO foo (a, b, c) SELECT d, e, f FROM baz"))
  (is (= (format {:insert-into [[:foo [:a :b :c]] {:select [:d :e :f] :from [:baz]}]})
         ["INSERT INTO foo (a, b, c) SELECT d, e, f FROM baz"])))

(deftest insert-into-namespaced
  ;; un-namespaced: works as expected:
  (is (= (format {:insert-into :foo :values [{:foo/id 1}]})
         ["INSERT INTO foo (id) VALUES (?)" 1]))
  (is (= (format {:insert-into :foo :columns [:foo/id] :values [[2]]})
         ["INSERT INTO foo (id) VALUES (?)" 2]))
  (is (= (format {:insert-into :foo :values [{:foo/id 1}]}
                 :namespace-as-table? true)
         ["INSERT INTO foo (id) VALUES (?)" 1]))
  (is (= (format {:insert-into :foo :columns [:foo/id] :values [[2]]}
                 :namespace-as-table? true)
         ["INSERT INTO foo (id) VALUES (?)" 2])))

(deftest exists-test
  (is (= (format {:exists {:select [:a] :from [:foo]}})
         ["EXISTS (SELECT a FROM foo)"]))
  (is (= (format {:select [:id]
                  :from [:foo]
                  :where [:exists {:select [1]
                                   :from [:bar]
                                   :where :deleted}]})
         ["SELECT id FROM foo WHERE EXISTS (SELECT ? FROM bar WHERE deleted)" 1])))

(deftest array-test
  (is (= (format {:insert-into :foo
                  :columns [:baz]
                  :values [[(sql/array [1 2 3 4])]]})
         ["INSERT INTO foo (baz) VALUES (ARRAY[?, ?, ?, ?])" 1 2 3 4]))
  (is (= (format {:insert-into :foo
                  :columns [:baz]
                  :values [[(sql/array ["one" "two" "three"])]]})
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
         ["SELECT foo FROM bar1 UNION SELECT foo FROM bar2"])))

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
    (is (= ["SELECT foo FROM bar WHERE (col1 mod ?) = (col2 + ?)" 4 4]
           (format {:select [:foo]
                    :from [:bar]
                    :where [:= [:mod :col1 4] [:+ :col2 4]]}))))

  (testing "Value context only applies to sequences in value/comparison spots"
    (let [sub {:select [:%sum.amount]
               :from [:bar]
               :where [:in :id ["id-1" "id-2"]]}]
      (is (= ["SELECT total FROM foo WHERE (SELECT sum(amount) FROM bar WHERE (id in (?, ?))) = total" "id-1" "id-2"]
             (format {:select [:total]
                      :from [:foo]
                      :where [:= sub :total]})))
      (is (= ["WITH t AS (SELECT sum(amount) FROM bar WHERE (id in (?, ?))) SELECT total FROM foo WHERE total = t" "id-1" "id-2"]
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
                    :values [[(sql/array [1 2 3 4])]]}
                   :parameterizer :none)
           ["INSERT INTO foo (baz) VALUES (ARRAY[1, 2, 3, 4])"])))

  (testing "union complex values"
    (is (= (format {:union [{:select [:foo] :from [:bar1]}
                            {:select [:foo] :from [:bar2]}]
                    :with [[[:bar {:columns [:spam :eggs]}]
                            {:values [[1 2] [3 4] [5 6]]}]]}
                   :parameterizer :none)
           ["WITH bar (spam, eggs) AS (VALUES (1, 2), (3, 4), (5, 6)) SELECT foo FROM bar1 UNION SELECT foo FROM bar2"]))))

(deftest where-and
  (testing "should ignore a nil predicate"
    (is (= (format {:where [:and [:= :foo "foo"] [:= :bar "bar"] nil]}
                   :parameterizer :postgresql)
           ["WHERE (foo = $1 AND bar = $2)" "foo" "bar"]))))


(defmethod parameterize :single-quote [_ value pname] (str \' value \'))
(defmethod parameterize :mysql-fill [_ value pname] "?")

(deftest customized-parameterizer
  (testing "should fill param with single quote"
    (is (= (format {:where [:and [:= :foo "foo"] [:= :bar "bar"] nil]}
                   :parameterizer :single-quote)
           ["WHERE (foo = 'foo' AND bar = 'bar')" "foo" "bar"])))
  (testing "should fill param with ?"
    (is (= (format {:where [:and [:= :foo "foo"] [:= :bar "bar"] nil]}
                   :parameterizer :mysql-fill)
           ["WHERE (foo = ? AND bar = ?)" "foo" "bar"]))))


(deftest set-before-from ; issue 235
  (is (=
       ["UPDATE `films` `f` SET `kind` = `c`.`test` FROM (SELECT `b`.`test` FROM `bar` `b` WHERE `b`.`id` = ?) `c` WHERE `f`.`kind` = ?" 1 "drama"]
       (->
         {:update [:films :f]
          :set0   {:kind :c.test}
          :from   [[{:select [:b.test]
                     :from   [[:bar :b]]
                     :where  [:= :b.id 1]} :c]]
          :where  [:= :f.kind "drama"]}
         (format :quoting :mysql)))))

(deftest set-after-join
  (is (=
       ["UPDATE `foo` INNER JOIN `bar` ON `bar`.`id` = `foo`.`bar_id` SET `a` = ? WHERE `bar`.`b` = ?" 1 42]
       (->
         {:update :foo
          :join   [:bar [:= :bar.id :foo.bar_id]]
          :set    {:a 1}
          :where  [:= :bar.b 42]}
         (format :quoting :mysql))))
  (is (=
       ["UPDATE `foo` INNER JOIN `bar` ON `bar`.`id` = `foo`.`bar_id` SET `a` = ? WHERE `bar`.`b` = ?" 1 42]
       (->
         {:update :foo
          :join   [:bar [:= :bar.id :foo.bar_id]]
          :set1   {:a 1}
          :where  [:= :bar.b 42]}
         (format :quoting :mysql)))))

(deftest delete-from-test
  (is (= ["DELETE FROM `foo` WHERE `foo`.`id` = ?" 42]
         (-> {:delete-from :foo
              :where [:= :foo.id 42]}
             (format :quoting :mysql)))))

(deftest delete-test
  (is (= ["DELETE `t1`, `t2` FROM `table1` `t1` INNER JOIN `table2` `t2` ON `t1`.`fk` = `t2`.`id` WHERE `t1`.`bar` = ?" 42]
         (-> {:delete [:t1 :t2]
              :from [[:table1 :t1]]
              :join [[:table2 :t2] [:= :t1.fk :t2.id]]
              :where [:= :t1.bar 42]}
             (format :quoting :mysql)))))

(deftest truncate-test
  (is (= ["TRUNCATE `foo`"]
         (-> {:truncate :foo}
             (format :quoting :mysql)))))

(deftest inlined-values-are-stringified-correctly
  (is (= ["SELECT foo, bar, NULL"]
         (format {:select [(honeysql.core/inline "foo")
                           (honeysql.core/inline :bar)
                           (honeysql.core/inline nil)]}))))

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
  (is (= ["SELECT foo FROM bar INNER JOIN table t ON TRUE"]
         (format {:select [:foo]
                  :from [:bar]
                  :join [[:table :t] true]}))))

(deftest cross-join-test
  (is (= ["SELECT * FROM foo CROSS JOIN bar"]
         (format {:select [:*]
                  :from [:foo]
                  :cross-join [:bar]})))
  (is (= ["SELECT * FROM foo f CROSS JOIN bar b"]
         (format {:select [:*]
                  :from [[:foo :f]]
                  :cross-join [[:bar :b]]}))))

(deftest issue-299-test
  (let [name    "test field"
        ;; this was being rendered inline into the SQL
        ;; creating an injection vulnerability (v1 only)
        ;; the context for seq->sql here seems to be the
        ;; 'regular' one so it tries to treat this as an
        ;; alias: 'value alias' -- the fix was to make it
        ;; a function context so it becomes (TRUE, ?):
        enabled [true, "); SELECT case when (SELECT current_setting('is_superuser'))='off' then pg_sleep(0.2) end; -- "]]
    (is (= ["INSERT INTO table (name, enabled) VALUES (?, (TRUE, ?))" name (second enabled)]
           (format {:insert-into :table
                    :values [{:name name
                              :enabled enabled}]})))))