(ns loom.test.network-simplex
  (:require
   [loom.network-simplex :refer [build-graph solve]]
   #?(:cljs [cljs.test    :as t :refer-macros [is are deftest testing run-tests]]
      :clj  [clojure.test :as t :refer        [is are deftest testing]])))

;; The majority of these tests are ported to cljc from networkx's python implementation:
;; https://github.com/networkx/networkx/blob/master/networkx/algorithms/flow/tests/test_mincost.py

;; All credit is due to the networkx team, whose code comments are also included below:

(def simple-graph
  (build-graph
   [[:a :b {:capacity 4 :cost 3}]
    [:a :c {:capacity 10 :cost 6}]
    [:b :d {:capacity 9 :cost 1}]
    [:c :d {:capacity 5 :cost 2}]]
   [[:a {:demand -5}]
    [:d {:demand 5}]]))

(deftest simple-test
  (testing "Simple graph"
    (let [g simple-graph
          [cost flow] (solve g)]
      (is (= cost 24))
      (is (= flow {:a {:b 4 :c 1}
                   :b {:d 4}
                   :c {:d 1}})))))

(def hex-graph
  ;; Algorithms in Java, Part 5: Graph Algorithms (third edition)
  ;; Figure 22.41.
  (build-graph
   [[:a :b {:capacity 3 :cost 3}]
    [:a :c {:capacity 3 :cost 1}]
    [:b :d {:capacity 2 :cost 1}]
    [:b :e {:capacity 2 :cost 1}]
    [:c :d {:capacity 1 :cost 4}]
    [:c :e {:capacity 2 :cost 2}]
    [:d :f {:capacity 2 :cost 2}]
    [:e :f {:capacity 2 :cost 1}]]
   [[:a {:demand -4}]
    [:f {:demand 4}]]))

(deftest hex-test
  (testing "Hex graph"
    (let [g hex-graph
          [cost flow] (solve g)]
      (is (= cost 20))
      (is (= flow
             {:a {:b 2 :c 2}
              :b {:d 2 :e 0}
              :c {:e 2 :d 0}
              :d {:f 2}
              :e {:f 2}})))))

(def trans-graph
  (build-graph
   [[:a :c {:cost 3}]
    [:r :a {:cost 2}]
    [:b :a {:cost 9}]
    [:r :c {:cost 0}]
    [:b :r {:cost -6}]
    [:c :d {:cost 5}]
    [:e :r {:cost 4}]
    [:e :f {:cost 3}]
    [:h :b {:cost 4}]
    [:f :d {:cost 7}]
    [:f :h {:cost 12}]
    [:g :d {:cost 12}]
    [:f :g {:cost -1}]
    [:h :g {:cost -10}]]
   [[:a {:demand 1}]
    [:b {:demand -2}]
    [:c {:demand -2}]
    [:d {:demand 3}]
    [:e {:demand -4}]
    [:f {:demand -4}]
    [:g {:demand 3}]
    [:h {:demand 2}]
    [:r {:demand 3}]]))

(deftest trans-shipment-test
  (testing "Trans-shipment"
    (let [g trans-graph
          [cost flow] (solve g)
          ]
      (is (= cost 41))
      (is (= flow
             {:a {:c 0},
              :b {:a 0, :r 2},
              :c {:d 3},
              :e {:r 3, :f 1},
              :f {:d 0, :g 3, :h 2},
              :g {:d 0}
              :h {:b 0, :g 0},
              :r {:a 1, :c 1}})))))

(def maxflow-mincost-graph
    (build-graph
   [[:s :a {:capacity 6}]
    [:s :c {:capacity 10 :cost 10}]
    [:a :b {:cost 6}]
    [:b :d {:capacity 8 :cost 7}]
    [:c :d {:cost 10}]
    [:d :t {:capacity 5 :cost 5}]]
   [[:s {:demand -5}]
    [:t {:demand 5}]]))

(deftest maxflow-mincost-test
  (testing "Maxflow-mincost"
    (let [g maxflow-mincost-graph
          [cost flow] (solve g)]
      (is (= cost 90))
      (is (= flow
             {:s {:a 5 :c 0}
              :a {:b 5}
              :b {:d 5}
              :c {:d 0}
              :d {:t 5}})))))

(def digraph1
  ;; From Bradley, S. P., Hax, A. C. and Magnanti, T. L.
  ;; Applied Mathematical Programming. Addison-Wesley, 1977.
  (build-graph
   [[1 2 {:capacity 15 :cost 4}]
    [1 3 {:capacity 8 :cost 4}]
    [2 3 {:cost 2}]
    [2 4 {:capacity 4 :cost 2}]
    [2 5 {:capacity 10 :cost 6}]
    [3 4 {:capacity 15 :cost 1}]
    [3 5 {:capacity 5 :cost 3}]
    [4 5 {:cost 2}]
    [5 3 {:capacity 4 :cost 1}]]
   [[1 {:demand -20}]
    [4 {:demand 5}]
    [5 {:demand 15}]]))

(deftest digraph1-test
  (testing "Digraph 1"
    (let [g digraph1
          [cost flow] (solve g)]
      (is (= cost 150))
      (is (= flow
             {1 {2 12, 3 8},
              2 {3 8, 4 4, 5 0},
              3 {4 11, 5 5},
              4 {5 10},
              5 {3 0}})))))

(def digraph2
  ;; Example from ticket #430 from mfrasca.
  ;; See slide 11 for original source:
  ;; http://www.cs.princeton.edu/courses/archive/spr03/cs226/lectures/mincost.4up.pdf
  (build-graph
   [[:s 1 {:capacity 12}]
    [:s 2 {:capacity 6}]
    [:s 3 {:capacity 14}]
    [1 2 {:capacity 11 :cost 4}]
    [2 3 {:capacity 9 :cost 6}]
    [1 4 {:capacity 5 :cost 5}]
    [1 5 {:capacity 2 :cost 12}]
    [2 5 {:capacity 4 :cost 4}]
    [2 6 {:capacity 2 :cost 6}]
    [3 6 {:capacity 31 :cost 3}]
    [4 5 {:capacity 18 :cost 4}]
    [5 6 {:capacity 9 :cost 5}]
    [4 :t {:capacity 3}]
    [5 :t {:capacity 7}]
    [6 :t {:capacity 22}]]
   [[:s {:demand -32}]
    [:t {:demand 32}]]))

(deftest digraph2-test
  (testing "Digraph 2"
    (let [g digraph2
          [cost flow] (solve g)]
      (is (= cost 193))
      (is (= flow
             {1 {2 6, 4 5, 5 1},
              2 {3 6, 5 4, 6 2},
              3 {6 20},
              4 {5 2, :t 3},
              5 {6 0, :t 7}
              6 {:t 22}
              :s {1 12, 2 6, 3 14}})))))

(def digraph3
  ;; Combinatorial Optimization: Algorithms and Complexity,
  ;; Papadimitriou Steiglitz at page 140 has an example, 7.1, but that
  ;; admits multiple solutions, so I alter it a bit.
  ;; From ticket #430 by mfrasca.
  (build-graph
   [[:s :a {:capacity 2 :cost 4}]
    [:s :b {:capacity 2 :cost 1}]
    [:a :b {:capacity 5 :cost 2}]
    [:a :t {:capacity 1 :cost 5}]
    [:b :a {:capacity 1 :cost 3}]
    [:b :t {:capacity 3 :cost 2}]]
   [[:s {:demand -4}]
    [:t {:demand 4}]]))

(deftest digraph3-test
  (testing "Digraph 3"
    (let [g digraph3
          [cost flow] (solve g)]
      ;; PS.ex.7.1: testing main function
      (is (= cost 23))
      (is (= flow
             {:s {:a 2, :b 2}
              :a {:b 1, :t 1}
              :b {:a 0, :t 3}})))))

(def digon
  (build-graph
   [[1 2 {:capacity 3 :cost 600000}]
    [2 1 {:capacity 2 :cost 0}]
    [2 3 {:capacity 5 :cost 714285}]
    [3 2 {:capacity 1 :cost 0}]]
   [[2 {:demand -4}]
    [3 {:demand 4}]]))

(deftest digon-test
  (testing "Digon"
    (let [g digon
          [cost flow] (solve g)]
      (is (= cost 2857140))
      (is (= flow
             {1 {2 0}
              2 {1 0, 3 4}
              3 {2 0}})))))

(def neg-self-loop
  (build-graph
   [[1 1 {:capacity 2 :cost -1}]]
   []))

(deftest neg-self-loop-test
  ;; Negative selfloops should cause an exception if uncapacitated and
  ;; always be saturated otherwise.
  (testing "Negative self-loops"
    (let [g neg-self-loop
          [cost flow] (solve g)]
      (is (= cost -2))
      (is (= flow
             {1 {1 2}})))))

(def bone-shaped
  (build-graph
   [[0 1 {:capacity 4}]
    [0 2 {:capacity 4}]
    [4 3 {:capacity 4}]
    [5 3 {:capacity 4}]
    [0 3 {:capacity 0}]]
   [[0 {:demand -4}]
    [1 {:demand 2}]
    [2 {:demand 2}]
    [3 {:demand 4}]
    [4 {:demand -2}]
    [5 {:demand -2}]]))

(deftest bone-shaped-test
  (testing "Bone-shaped"
    (let [g bone-shaped
          [cost flow] (solve g)]
      (is (= cost 0))
      (is (= flow
             {0 {1 2, 2 2, 3 0}
              4 {3 2}
              5 {3 2}})))))
