(ns loom.test.compliance-tester 
  "Provides compliance tests for graph protocols."
  (:require [loom.graph :refer [add-edges add-nodes nodes edges has-node? has-edge?
                                successors out-degree remove-nodes remove-edges
                                add-edges* transpose predecessors in-degree weight]]
            [loom.attr :as attr]
            #?@(:clj [[clojure.test :refer :all]]))
  #?@(:cljs [(:require-macros [cljs.test :refer (deftest testing are is)])]))

(defn graph-test
  "Collection of simple graph tests. Uses the provided empty graph instance, g, to create 
  various graphs and test the implementation."
  [g]
  (let [g1 (-> g (add-edges [1 2] [1 3] [2 3]) (add-nodes 4))
        g4 (-> g1 (add-edges [5 6] [7 8]) (add-nodes 9))
        g5 g]
    (testing "Construction, nodes, edges"
      (are [expected got] (= expected got)
           #{1 2 3 4} (set (nodes g1))
           #{[1 2] [2 1] [1 3] [3 1] [2 3] [3 2]} (set (edges g1))
           #{1 2 3 4 5 6 7 8 9} (set (nodes g4))
           #{[1 2] [2 1] [1 3] [3 1] [2 3]
             [3 2] [5 6] [6 5] [7 8] [8 7]} (set (edges g4))
             #{} (set (nodes g5))
             #{} (set (edges g5))
             true (has-node? g1 4)
             true (has-edge? g1 1 2)
             false (has-node? g1 5)
             false (has-edge? g1 4 1)))
    (testing "Successors"
      (are [expected got] (= expected got)
           #{2 3} (set (successors g1 1))
           #{1 2} (set (successors g1 3))
           #{} (set (successors g1 4))
           2 (out-degree g1 1)
           2 (out-degree g1 3)
           0 (out-degree g1 4)))
    (testing "Add & remove"
      (are [expected got] (= expected got)
           #{1 2 3 4 5} (set (nodes (add-nodes g1 5)))
           #{:a :b :c} (set (nodes (add-nodes g5 :a :b :c)))
           #{{:id 1} {:id 2}} (set (nodes (add-nodes g5 {:id 1} {:id 2})))
           #{[1 2] [2 1]} (set (edges (add-edges g5 [1 2])))
           #{1 2} (set (nodes (remove-nodes g1 3 4)))
           #{[1 2] [2 1]} (set (edges (remove-nodes g1 3 4)))
           #{1 2 3 4} (set (nodes (remove-edges g1 [1 2] [2 1] [1 3] [3 1])))
           #{[2 3] [3 2]} (set (edges (remove-edges
                                       g1 [1 2] [2 1] [1 3] [3 1])))))
    (testing "Adding multiple edges"
      (are [expected got] (= expected got)
           #{1 2 3 4 5} (set (nodes (add-edges* g5 [[1 2] [2 3] [3 4] [4 5]])))
           #{[1 2] [2 1] [2 3] [3 2] [3 4] [4 3] [4 5] [5 4]} (set (edges (add-edges* g5 [[1 2] [2 3] [3 4] [4 5]])))))))

(defn digraph-test
  "Test the provided digraph implementation. The dg parameter is a digraph instance and may be used to construct
  other digraphs for testing."
  [dg]
  (let [g1 (-> dg (add-edges [1 2] [1 3] [2 3]) (add-nodes 4))
        g4 (-> g1 (add-edges [5 6] [6 5] [7 8]) (add-nodes 9))
        g5 dg
        g6 (transpose g1)]
    (testing "Construction, nodes, edges"
      (are [expected got] (= expected got)
           #{1 2 3 4} (set (nodes g1))
           #{1 2 3 4} (set (nodes g6))
           #{[1 2] [1 3] [2 3]} (set (edges g1))
           #{[2 1] [3 1] [3 2]} (set (edges g6))
           #{1 2 3 4 5 6 7 8 9} (set (nodes g4))
           #{[1 2] [1 3] [2 3] [5 6] [6 5] [7 8]} (set (edges g4))
           true (has-node? g1 4)
           true (has-edge? g1 1 2)
           false (has-node? g1 5)
           false (has-edge? g1 2 1)))
    (testing "Successors"
      (are [expected got] (= expected got)
           #{2 3} (set (successors g1 1))
           #{} (set (successors g1 3))
           #{} (set (successors g1 4))
           2 (out-degree g1 1)
           0 (out-degree g1 3)
           0 (out-degree g1 4)
           #{1 2} (set (predecessors g1 3))
           #{} (set (predecessors g1 1))
           2 (in-degree g1 3)
           0 (in-degree g1 1)
           #{1 2} (set (successors g6 3))
           #{} (set (successors g6 1))
           2 (out-degree g6 3)
           0 (out-degree g6 1)))
    (testing "Add & remove"
      (are [expected got] (= expected got)
           #{1 2 3 4 5} (set (nodes (add-nodes g1 5)))
           #{:a :b :c} (set (nodes (add-nodes g5 :a :b :c)))
           #{{:id 1} {:id 2}} (set (nodes (add-nodes g5 {:id 1} {:id 2})))
           #{[1 2]} (set (edges (add-edges g5 [1 2])))
           #{1 2} (set (nodes (remove-nodes g1 3 4)))
           #{[1 2]} (set (edges (remove-nodes g1 3 4)))
           #{1 2 3 4} (set (nodes (remove-edges g1 [1 2] [1 3])))
           #{[2 3]} (set (edges (remove-edges g1 [1 2] [1 3])))))))

(defn weighted-graph-test
  [wg]
  (let [g1 (-> wg (add-edges [1 2 77] [1 3 88] [2 3 99]) (add-nodes 4))
        g4 (-> g1 (add-edges [5 6 88] [7 8]) (add-nodes 9))
        g5 wg]
    (testing "Construction, nodes, edges"
      (are [expected got] (= expected got)
           #{1 2 3 4} (set (nodes g1))
           #{[1 2] [2 1] [1 3] [3 1] [2 3] [3 2]} (set (edges g1))
           #{1 2 3 4 5 6 7 8 9} (set (nodes g4))
           #{[1 2] [2 1] [1 3] [3 1] [2 3]
             [3 2] [5 6] [6 5] [7 8] [8 7]} (set (edges g4))
             #{} (set (nodes g5))
             #{} (set (edges g5))
             true (has-node? g1 4)
             true (has-edge? g1 1 2)
             false (has-node? g1 5)
             false (has-edge? g1 4 1)))
    (testing "Successors"
      (are [expected got] (= expected got)
           #{2 3} (set (successors g1 1))
           #{1 2} (set (successors g1 3))
           #{} (set (successors g1 4))
           2 (out-degree g1 1)
           2 (out-degree g1 3)
           0 (out-degree g1 4)))
    (testing "Add & remove"
      (are [expected got] (= expected got)
           #{1 2 3 4 5} (set (nodes (add-nodes g1 5)))
           #{:a :b :c} (set (nodes (add-nodes g5 :a :b :c)))
           #{{:id 1} {:id 2}} (set (nodes (add-nodes g5 {:id 1} {:id 2})))
           #{[1 2] [2 1]} (set (edges (add-edges g5 [1 2])))
           #{1 2} (set (nodes (remove-nodes g1 3 4)))
           #{[1 2] [2 1]} (set (edges (remove-nodes g1 3 4)))
           #{1 2 3 4} (set (nodes (remove-edges g1 [1 2] [2 1] [1 3] [3 1])))
           #{[2 3] [3 2]} (set (edges (remove-edges
                                       g1 [1 2] [2 1] [1 3] [3 1])))))
    (testing "Weight"
      (are [expected got] (= expected got)
           77 (weight g1 1 2)
           88 (weight g4 6 5)
           1 (weight g4 7 8)))))

(defn weighted-digraph-test
  [dwg]
  (let [g1 (-> dwg (add-edges [1 2 77] [1 3 88] [2 3 99]) (add-nodes 4))
        g4 (-> g1 (add-edges [5 6 88] [6 5 88] [7 8]) (add-nodes 9))
        g5 dwg
        g6 (transpose g1)]
    (testing "Construction, nodes, edges"
      (are [expected got] (= expected got)
           #{1 2 3 4} (set (nodes g1))
           #{1 2 3 4} (set (nodes g6))
           #{[1 2] [1 3] [2 3]} (set (edges g1))
           #{[2 1] [3 1] [3 2]} (set (edges g6))
           #{1 2 3 4 5 6 7 8 9} (set (nodes g4))
           #{[1 2] [1 3] [2 3] [5 6] [6 5] [7 8]} (set (edges g4))
           #{} (set (nodes g5))
           #{} (set (edges g5))
           true (has-node? g1 4)
           true (has-edge? g1 1 2)
           false (has-node? g1 5)
           false (has-edge? g1 2 1)))
    (testing "Successors"
      (are [expected got] (= expected got)
           #{2 3} (set (successors g1 1))
           #{} (set (successors g1 3))
           #{} (set (successors g1 4))
           2 (out-degree g1 1)
           0 (out-degree g1 3)
           0 (out-degree g1 4)
           #{1 2} (set (predecessors g1 3))
           #{} (set (predecessors g1 1))
           2 (in-degree g1 3)
           0 (in-degree g1 1)
           #{1 2} (set (successors g6 3))
           #{} (set (successors g6 1))
           2 (out-degree g6 3)
           0 (out-degree g6 1)))
    (testing "Add & remove"
      (are [expected got] (= expected got)
           #{1 2 3 4 5} (set (nodes (add-nodes g1 5)))
           #{:a :b :c} (set (nodes (add-nodes g5 :a :b :c)))
           #{{:id 1} {:id 2}} (set (nodes (add-nodes g5 {:id 1} {:id 2})))
           #{[1 2]} (set (edges (add-edges g5 [1 2])))
           #{1 2} (set (nodes (remove-nodes g1 3 4)))
           #{[1 2]} (set (edges (remove-nodes g1 3 4)))
           #{1 2 3 4} (set (nodes (remove-edges g1 [1 2] [1 3])))
           #{[2 3]} (set (edges (remove-edges g1 [1 2] [1 3])))))
    (testing "Weight"
      (are [expected got] (= expected got)
           77 (weight g1 1 2)
           77 (weight g6 2 1)
           88 (weight g4 6 5)
           1 (weight g4 7 8)))))
