(ns loom.test.graph
  (:require [loom.graph :refer (graph digraph weighted-graph weighted-digraph
                                      nodes edges has-node? has-edge? transpose fly-graph
                                      weight graph? Graph directed? Digraph weighted?
                                      WeightedGraph subgraph add-path add-cycle)]
            [loom.attr :as attr]
            #?@(:clj [[clojure.test :refer (deftest testing are is)]])
            [loom.test.compliance-tester :refer [graph-test digraph-test
                                                 weighted-graph-test weighted-digraph-test]])
  #?@(:cljs [(:require-macros [cljs.test :refer (deftest testing are is)])]))

(deftest test-default-implementations
  (graph-test (graph))
  (digraph-test (digraph))
  (weighted-graph-test (weighted-graph))
  (weighted-digraph-test (weighted-digraph)))

(deftest build-graph-test
  (let [g1 (graph [1 2] [1 3] [2 3] 4)
        g2 (graph {1 [2 3] 2 [3] 4 []})
        g3 (graph g1)
        g4 (graph g3 (digraph [5 6]) [7 8] 9)
        g5 (graph)]
    (testing "Construction, nodes, edges"
      (are [expected got] (= expected got)
           #{1 2 3 4} (set (nodes g1))
           #{[1 2] [2 1] [1 3] [3 1] [2 3] [3 2]} (set (edges g1))
           (set (nodes g2)) (set (nodes g1))
           (set (edges g2)) (set (edges g1))
           (set (nodes g3)) (set (nodes g1))
           (set (nodes g3)) (set (nodes g1))
           #{1 2 3 4 5 6 7 8 9} (set (nodes g4))
           #{[1 2] [2 1] [1 3] [3 1] [2 3]
             [3 2] [5 6] [6 5] [7 8] [8 7]} (set (edges g4))
             #{} (set (nodes g5))
             #{} (set (edges g5))
             true (has-node? g1 4)
             true (has-edge? g1 1 2)
             false (has-node? g1 5)
             false (has-edge? g1 4 1)))))

(deftest simple-graph-test
  (let [g1 (graph [1 2] [1 3] [2 3] 4)
        g2 (graph {1 [2 3] 2 [3] 4 []})
        g3 (graph g1)
        g4 (graph g3 (digraph [5 6]) [7 8] 9)
        g5 (graph)]
    (testing "Construction, nodes, edges"
      (are [expected got] (= expected got)
           #{1 2 3 4} (set (nodes g1))
           #{[1 2] [2 1] [1 3] [3 1] [2 3] [3 2]} (set (edges g1))
           (set (nodes g2)) (set (nodes g1))
           (set (edges g2)) (set (edges g1))
           (set (nodes g3)) (set (nodes g1))
           (set (nodes g3)) (set (nodes g1))
           #{1 2 3 4 5 6 7 8 9} (set (nodes g4))
           #{[1 2] [2 1] [1 3] [3 1] [2 3]
             [3 2] [5 6] [6 5] [7 8] [8 7]} (set (edges g4))
             #{} (set (nodes g5))
             #{} (set (edges g5))
             true (has-node? g1 4)
             true (has-edge? g1 1 2)
             false (has-node? g1 5)
             false (has-edge? g1 4 1)))))

(deftest simple-digraph-test
  (let [g1 (digraph [1 2] [1 3] [2 3] 4)
        g2 (digraph {1 [2 3] 2 [3] 4 []})
        g3 (digraph g1)
        g4 (digraph g3 (graph [5 6]) [7 8] 9)
        g5 (digraph)
        g6 (transpose g1)]
    (testing "Construction, nodes, edges"
      (are [expected got] (= expected got)
           #{1 2 3 4} (set (nodes g1))
           #{1 2 3 4} (set (nodes g6))
           #{[1 2] [1 3] [2 3]} (set (edges g1))
           #{[2 1] [3 1] [3 2]} (set (edges g6))
           (set (nodes g2)) (set (nodes g1))
           (set (edges g2)) (set (edges g1))
           (set (nodes g3)) (set (nodes g1))
           (set (nodes g3)) (set (nodes g1))
           #{1 2 3 4 5 6 7 8 9} (set (nodes g4))
           #{[1 2] [1 3] [2 3] [5 6] [6 5] [7 8]} (set (edges g4))
           #{} (set (nodes g5))
           #{} (set (edges g5))
           true (has-node? g1 4)
           true (has-edge? g1 1 2)
           false (has-node? g1 5)
           false (has-edge? g1 2 1)))))

(deftest simple-weighted-graph-test
  (let [g1 (weighted-graph [1 2 77] [1 3 88] [2 3 99] 4)
        g2 (weighted-graph {1 {2 77 3 88} 2 {3 99} 4 []})
        g3 (weighted-graph g1)
        g4 (weighted-graph g3 (weighted-digraph [5 6 88]) [7 8] 9)
        g5 (weighted-graph)]
    (testing "Construction, nodes, edges"
      (are [expected got] (= expected got)
           #{1 2 3 4} (set (nodes g1))
           #{[1 2] [2 1] [1 3] [3 1] [2 3] [3 2]} (set (edges g1))
           (set (nodes g2)) (set (nodes g1))
           (set (edges g2)) (set (edges g1))
           (set (nodes g3)) (set (nodes g1))
           (set (nodes g3)) (set (nodes g1))
           #{1 2 3 4 5 6 7 8 9} (set (nodes g4))
           #{[1 2] [2 1] [1 3] [3 1] [2 3]
             [3 2] [5 6] [6 5] [7 8] [8 7]} (set (edges g4))
             #{} (set (nodes g5))
             #{} (set (edges g5))
             true (has-node? g1 4)
             true (has-edge? g1 1 2)
             false (has-node? g1 5)
             false (has-edge? g1 4 1)))))

(deftest simple-weighted-digraph-test
  (let [g1 (weighted-digraph [1 2 77] [1 3 88] [2 3 99] 4)
        g2 (weighted-digraph {1 {2 77 3 88} 2 {3 99} 4 []})
        g3 (weighted-digraph g1)
        g4 (weighted-digraph g3 (weighted-graph [5 6 88]) [7 8] 9)
        g5 (weighted-digraph)
        g6 (transpose g1)]
    (testing "Construction, nodes, edges"
      (are [expected got] (= expected got)
           #{1 2 3 4} (set (nodes g1))
           #{1 2 3 4} (set (nodes g6))
           #{[1 2] [1 3] [2 3]} (set (edges g1))
           #{[2 1] [3 1] [3 2]} (set (edges g6))
           (set (nodes g2)) (set (nodes g1))
           (set (edges g2)) (set (edges g1))
           (set (nodes g3)) (set (nodes g1))
           (set (nodes g3)) (set (nodes g1))
           #{1 2 3 4 5 6 7 8 9} (set (nodes g4))
           #{[1 2] [1 3] [2 3] [5 6] [6 5] [7 8]} (set (edges g4))
           #{} (set (nodes g5))
           #{} (set (edges g5))
           true (has-node? g1 4)
           true (has-edge? g1 1 2)
           false (has-node? g1 5)
           false (has-edge? g1 2 1)))))

(deftest fly-graph-test
  (let [fg1 (fly-graph :nodes [1 2 3]
                       :successors #(if (= 3 %) [1] [(inc %)])
                       :weight (constantly 88))
        fg2 (fly-graph :successors #(if (= 3 %) [1] [(inc %)])
                       :start 1)]
    (testing "Construction, nodes, edges"
      (are [expected got] (= expected got)
           #{1 2 3} (set (nodes fg1))
           #{1 2 3} (set (nodes fg2))
           #{[1 2] [2 3] [3 1]} (set (edges fg1))
           #{[1 2] [2 3] [3 1]} (set (edges fg2))
           88 (weight fg1 1 2)))
    (testing "Predicates"
      (are [expected got] (= expected got)
           1 (has-node? fg1 1)
           nil (has-node? fg1 11)
           2 (has-node? fg2 2)
           nil (has-node? fg2 11)))
    ;; TODO: finish
    ))

(deftest merge-graph-test
  (testing "two graphs with attributes"
    (let [g1 (attr/add-attr (digraph [1 2] 3 [1 4]) 1 :label "One")
          g2 (attr/add-attr (digraph [1 3] [3 5]) 5 :label "Five")
          merged (digraph g1 g2)]
      (is (= "One"  (attr/attr merged 1 :label)))
      (is (= "Five" (attr/attr merged 5 :label)))))
  (testing "with two weighted graphs"
    (let [g1 (attr/add-attr (weighted-graph [1 2] 3 [1 4]) 1 :label "One")
          g2 (attr/add-attr (weighted-graph [1 3] [3 5]) 5 :label "Five")
          merged (weighted-graph g1 g2)]
      (is (= "One"  (attr/attr merged 1 :label)))
      (is (= "Five" (attr/attr merged 5 :label))))))

(deftest utilities-test
  (testing "Predicates"
    (are [expected got] (= expected got)
         true (every? true? (map graph? [(graph [1 2])
                                         (digraph [1 2])
                                         (weighted-graph [1 2])
                                         (weighted-digraph [1 2])
                                         (fly-graph :successors [1 2])
                                         (reify Graph)]))
         true (every? true? (map directed? [(digraph [1 2])
                                            (weighted-digraph [1 2])
                                            (fly-graph :predecessors [1 2])
                                            (reify Digraph)]))
         true (every? true? (map weighted? [(weighted-graph [1 2])
                                            (weighted-digraph [1 2])
                                            (fly-graph :weight (constantly 1))
                                            (reify WeightedGraph)]))))
  (testing "Adders"
    (let [g (weighted-digraph [1 2] [2 3] [3 1])
          sg (subgraph g [1 2])
          pg (add-path (digraph) 1 2 3 4 5)
          cg (add-cycle (digraph) 1 2 3)]
      (are [expected got] (= expected got)
           #{1 2} (set (nodes sg))
           #{[1 2]} (set (edges sg))
           true (graph? sg)
           true (directed? sg)
           true (weighted? sg)
           #{[1 2] [2 3] [3 4] [4 5]} (set (edges pg))
           #{[1 2] [2 3] [3 1]} (set (edges cg))))))
