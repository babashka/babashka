(ns edn-query-language.core-test
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as s.test]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.clojure-test :as test]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as props]
            [edn-query-language.core :as eql]
            [edn-query-language.gen :as eql-gen]))

(s.test/instrument)

;; spec tests

(defn valid-queries-props []
  (props/for-all [query (eql-gen/make-gen {} ::eql-gen/gen-query)]
    (s/valid? ::eql/query query)))

(test/defspec generator-makes-valid-queries {:max-size 12 :num-tests 50} (valid-queries-props))

(comment
  (tc/quick-check 50 (valid-queries-props) :max-size 12))

;; lib tests

(defn remove-meta [x]
  (eql/transduce-children (map #(dissoc % :meta)) x))

(defn tquery->ast [query]
  (remove-meta (eql/query->ast query)))

(deftest test-query->ast
  (testing "empty query"
    (is (= (tquery->ast [])
           {:type :root, :children []})))

  (testing "single property"
    (is (= (tquery->ast [:a])
           {:type :root, :children [{:type :prop, :dispatch-key :a, :key :a}]})))

  (testing "multiple properties"
    (is (= (tquery->ast [:a :b])
           {:type     :root,
            :children [{:type :prop, :dispatch-key :a, :key :a}
                       {:type :prop, :dispatch-key :b, :key :b}]})))

  (testing "blank join"
    (is (= (tquery->ast [{:a []}])
           {:type     :root,
            :children [{:type :join, :dispatch-key :a, :key :a, :query [], :children []}]})))

  (testing "simple join"
    (is (= (tquery->ast [{:a [:b]}])
           {:type     :root,
            :children [{:type         :join,
                        :dispatch-key :a,
                        :key          :a,
                        :query        [:b],
                        :children     [{:type :prop, :dispatch-key :b, :key :b}]}]})))

  (testing "param expression"
    (is (= (tquery->ast ['(:a {:foo "bar"})])
           {:type     :root,
            :children [{:type         :prop,
                        :dispatch-key :a,
                        :key          :a,
                        :params       {:foo "bar"},}]})))

  (testing "param join"
    (is (= (tquery->ast ['({:a [:sub]} {:foo "bar"})])
           {:type     :root,
            :children [{:type         :join,
                        :dispatch-key :a,
                        :key          :a,
                        :query        [:sub],
                        :children     [{:type :prop, :dispatch-key :sub, :key :sub}],
                        :params       {:foo "bar"},}]})))

  (testing "param join 2"
    (is (= (tquery->ast [{'(:a {:foo "bar"}) [:sub]}])
           {:type     :root
            :children [{:children     [{:dispatch-key :sub
                                        :key          :sub
                                        :type         :prop}]
                        :dispatch-key :a
                        :key          :a
                        :params       {:foo "bar"}
                        :query        [:sub]
                        :type         :join}]})))

  (testing "union query"
    (is (= (tquery->ast [{:foo {:a [:b]
                                :c [:d]}}])
           {:type     :root,
            :children [{:type         :join,
                        :dispatch-key :foo,
                        :key          :foo,
                        :query        {:a [:b], :c [:d]},
                        :children     [{:type     :union,
                                        :query    {:a [:b], :c [:d]},
                                        :children [{:type      :union-entry,
                                                    :union-key :a,
                                                    :query     [:b],
                                                    :children  [{:type :prop, :dispatch-key :b, :key :b}]}
                                                   {:type      :union-entry,
                                                    :union-key :c,
                                                    :query     [:d],
                                                    :children  [{:type :prop, :dispatch-key :d, :key :d}]}]}]}]})))

  (testing "unbounded recursion"
    (is (= (tquery->ast '[{:item [:a :b {:parent ...}]}])
           '{:type     :root,
             :children [{:type         :join,
                         :dispatch-key :item,
                         :key          :item,
                         :query        [:a :b {:parent ...}],
                         :children     [{:type :prop, :dispatch-key :a, :key :a}
                                        {:type :prop, :dispatch-key :b, :key :b}
                                        {:type :join, :dispatch-key :parent, :key :parent, :query ...}]}]})))

  (testing "bounded recursion"
    (is (= (tquery->ast '[{:item [:a :b {:parent 5}]}])
           '{:type     :root,
             :children [{:type         :join,
                         :dispatch-key :item,
                         :key          :item,
                         :query        [:a :b {:parent 5}],
                         :children     [{:type :prop, :dispatch-key :a, :key :a}
                                        {:type :prop, :dispatch-key :b, :key :b}
                                        {:type :join, :dispatch-key :parent, :key :parent, :query 5}]}]})))

  (testing "mutation expression"
    (is (= (tquery->ast ['(a {})])
           '{:type     :root,
             :children [{:dispatch-key a,
                         :key          a,
                         :params       {},
                         :type         :call}]})))

  (testing "mutation join expression"
    (is (= (tquery->ast [{'(a {}) [:sub-query]}])
           '{:type     :root,
             :children [{:dispatch-key a,
                         :key          a,
                         :params       {},
                         :type         :call,
                         :query        [:sub-query],
                         :children     [{:type :prop, :dispatch-key :sub-query, :key :sub-query}]}]}))))

(defn query<->ast-props []
  (props/for-all [query (eql-gen/make-gen {::eql-gen/gen-params
                                       (fn [_]
                                         (gen/map gen/keyword gen/string-alphanumeric))}
                          ::eql-gen/gen-query)]
    (let [ast (-> query
                  eql/query->ast
                  eql/ast->query
                  eql/query->ast)]
      (= ast (-> ast
                 eql/ast->query
                 eql/query->ast)))))

(test/defspec query-ast-roundtrip {:max-size 12 :num-tests 100} (query<->ast-props))

(comment
  (tc/quick-check 100 (query<->ast-props) :max-size 12))

(deftest test-ast->query
  (is (= (eql/ast->query {:type         :prop
                          :key          :foo
                          :dispatch-key :foo})
         [:foo]))

  (is (= (eql/ast->query {:type     :root
                          :children [{:type         :prop
                                      :dispatch-key :foo
                                      :key          :foo}]})
         [:foo])))

(deftest test-focus-subquery
  (is (= (eql/focus-subquery [] [])
         []))
  (is (= (eql/focus-subquery [:a :b :c] [])
         []))
  (is (= (eql/focus-subquery [:a :b :c] [:d])
         []))
  (is (= (eql/focus-subquery [:a :b :c] [:a])
         [:a]))
  (is (= (eql/focus-subquery [:a :b :c] [:a :b])
         [:a :b]))
  (is (= (eql/focus-subquery [:a {:b [:d]}] [:a :b])
         [:a {:b [:d]}]))
  (is (= (eql/focus-subquery [:a {:b [:c :d]}] [:a {:b [:c]}])
         [:a {:b [:c]}]))
  (is (= (eql/focus-subquery [:a '({:b [:c :d]} {:param "value"})] [:a {:b [:c]}])
         [:a '({:b [:c]} {:param "value"})]))

  ; in union case, keys absent from focus will be pulled anyway, given ones will focus
  (is (= (eql/focus-subquery [:a {:b {:c [:d :e]
                                       :f [:g :h]}}]
           [:a {:b {:f [:g]}}])
         [:a {:b {:c [:d :e] :f [:g]}}])))

(defn transduce-query [xform query]
  (->> query eql/query->ast
       (eql/transduce-children xform)
       eql/ast->query))

(deftest test-tranduce-children
  (is (= (transduce-query
           (comp (filter (comp #{:a :c} :key))
                 (map #(assoc % :params {:n 42})))
           [:a :b :c :d])
         '[(:a {:n 42}) (:c {:n 42})])))

(deftest test-merge-queries
  (is (= (eql/merge-queries nil nil)
         []))

  (is (= (eql/merge-queries [:a] nil)
         [:a]))

  (is (= (eql/merge-queries [] [])
         []))

  (is (= (eql/merge-queries [:a] [])
         [:a]))

  (is (= (eql/merge-queries [:a] [:a])
         [:a]))

  (is (= (eql/merge-queries [:a] [:b])
         [:a :b]))

  (is (= (eql/merge-queries [:a] [:b :c :d])
         [:a :b :c :d]))

  (is (= (eql/merge-queries [[:u/id 1]] [[:u/id 2]])
         [[:u/id 1] [:u/id 2]]))

  (is (= (eql/merge-queries [{:user [:name]}] [{:user [:email]}])
         [{:user [:name :email]}]))

  (is (= (eql/merge-queries [:a] [{:a [:x]}])
         [{:a [:x]}]))

  (is (= (eql/merge-queries [{:a [:x]}] [:a])
         [{:a [:x]}]))

  (testing "don't merge queries with different params"
    (is (= (eql/merge-queries ['({:user [:name]} {:login "u1"})]
             ['({:user [:email]} {:login "u2"})])
           nil)))

  (testing "don't merge queries with different params"
    (is (= (eql/merge-queries ['(:user {:login "u1"})]
             ['(:user {:login "u2"})])
           nil)))

  (testing "merge when params are same"
    (is (= (eql/merge-queries ['({:user [:name]} {:login "u1"})]
             ['({:user [:email]} {:login "u1"})])
           ['({:user [:name :email]} {:login "u1"})])))

  (testing "calls can't be merged when same name occurs"
    (is (= (eql/merge-queries ['(hello {:login "u1"})]
             ['(hello {:bla "2"})])
           nil)))

  (testing "even when parameters are the same"
    (is (= (eql/merge-queries ['(hello {:login "u1"})]
             ['(hello {:login "u1"})])
           nil))))

(deftest test-update-child
  (is (= (eql/update-child {:children [{:dispatch-key :id :key :id :type :prop}
                                       {:dispatch-key :parent :key :parent :query 3 :type :join}]
                            :type     :root}
           :parent update :query dec)
         {:children [{:dispatch-key :id :key :id :type :prop}
                     {:dispatch-key :parent :key :parent :query 2 :type :join}]
          :type     :root})))

(deftest update-recursive-depth-test
  (is (= (eql/update-recursive-depth
           {:children [{:dispatch-key :id :key :id :type :prop}
                       {:dispatch-key :parent :key :parent :query 3 :type :join}]
            :type     :root}
           :parent dec)
         {:children [{:dispatch-key :id :key :id :type :prop}
                     {:dispatch-key :parent :key :parent :query 2 :type :join}]
          :type     :root})))

(deftest test-mask-query
  (is (= (eql/mask-query [] [])
         []))
  (is (= (eql/mask-query [:foo :bar] [])
         []))
  (is (= (eql/mask-query [:foo :bar] [:foo])
         [:foo]))
  (is (= (eql/mask-query [:bar :foo] [:foo])
         [:foo]))
  (is (= (eql/mask-query [:foo {:bar [:inside]}] [:foo])
         [:foo]))
  (is (= (eql/mask-query ['(:foo {:bla "meh"}) :bar] [:foo])
         ['(:foo {:bla "meh"})]))
  (is (= (eql/mask-query [:foo {:bar [:inside :more]}] [:foo :bar])
         [:foo {:bar [:inside :more]}]))
  (is (= (eql/mask-query [:foo {:bar [:inside :more]}] [:foo {:bar [:inside]}])
         [:foo {:bar [:inside]}])))

(deftest test-normalize-query-variables
  (testing "blank query"
    (is (= (eql/normalize-query-variables [])
           [])))

  (testing "simple query"
    (is (= (eql/normalize-query-variables [:a :b :c])
           [:a :b :c])))

  (testing "normalize ident values"
    (is (= (eql/normalize-query-variables [[:foo "bar"]])
           [[:foo ::eql/var]])))

  (testing "normalize params"
    (is (= (eql/normalize-query-variables ['(:foo {:x 1 :y 2})])
           ['(:foo {:x ::eql/var :y ::eql/var})])))

  (testing "all together"
    (is (= (eql/normalize-query-variables '[:a :b {[:join "val"] [{(:c {:page 10}) [:d]}]}])
           '[:a :b
             {[:join ::eql/var]
              [({:c [:d]}
                 {:page ::eql/var})]}]))))

(deftest test-query-id
  (is (= (eql/query-id '[:a :b {[:join "val"] [{(:c {:page 10}) [:d]}]}])
         -61421281)))


(deftest shallow-conversion
  (testing "requesting shallow conversion will only convert the first layer of a query"
    (let [ast (eql/query->shallow-ast [:x
                                       {:y [{:z [:a]}]}
                                       {[:table 1] [:z {:other [:m :n]}]}
                                       {:ujoin {:u1 [:x] :u2 [:y]}}])]
      (is (= {:type     :root,
              :children [{:type :prop, :dispatch-key :x, :key :x}
                         {:type :join, :dispatch-key :y, :key :y, :query [{:z [:a]}]}
                         {:type :join, :dispatch-key :table, :key [:table 1], :query [:z {:other [:m :n]}]}
                         {:type :join, :dispatch-key :ujoin, :key :ujoin, :query {:u1 [:x], :u2 [:y]}}]}
            ast)))))


(deftest merge-asts-as-reduce-function
  (testing
    "init - when called with arity zero, it returns an empty ast"
    (is (= {:type     :root
            :children []}
          (transduce (map identity)
            eql/merge-asts
            []))))
  (testing
    "completion - when called with arity one, it should return its argument"
    (is (= {:children [{:dispatch-key :a
                        :key          :a
                        :type         :prop}]
            :type     :root}
          (transduce (map identity)
            eql/merge-asts
            [(eql/query->ast [:a])]))))
  (testing
    "step - the old arity 2. Should compose both nodes into a new node"
    (is (= {:children [{:dispatch-key :a
                        :key          :a
                        :type         :prop}
                       {:dispatch-key :b
                        :key          :b
                        :type         :prop}]
            :type     :root}
          (transduce (map identity)
            eql/merge-asts
            [(eql/query->ast [:a])
             (eql/query->ast [:b])])))))
