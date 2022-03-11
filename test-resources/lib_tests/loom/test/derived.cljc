(ns loom.test.derived
  (:require [loom.derived :refer [mapped-by nodes-filtered-by edges-filtered-by
                                  subgraph-reachable-from bipartite-subgraph]]
            [loom.graph :refer (graph digraph edges)]
            [loom.alg :refer (eql?)]
            #?@(:clj [[clojure.test :refer :all]]
                :cljs [cljs.test]))
  #?@(:cljs [(:require-macros [cljs.test :refer (deftest testing are is)])]))

(deftest derived-graph-test
  (let [g  (graph [1 2] [1 3] [2 3] 4)
        dg (digraph [1 2] [1 3] [2 3] 4)]

    (testing "mapped-by"
      (are [expected got] (= expected got)
        true (eql? g
                   (mapped-by identity g))
        true (eql? (graph [2 3] [2 4] [3 4] 5)
                   (mapped-by inc g))
        true (eql? (graph [2 0] [2 1] [0 1] 2)
                   (mapped-by #(mod % 3) g))
        ;; digraph
        true (eql? dg
                   (mapped-by identity dg))
        true (eql? (digraph [2 3] [2 4] [3 4] 5)
                   (mapped-by inc dg))
        true (eql? (digraph [2 0] [1 2] [1 0])
                   (mapped-by #(mod % 3) dg))))

    (testing "nodes filtered"
      (are [expected got] (= expected got)
        true (eql? (graph)
                   (nodes-filtered-by #{} g))
        true (eql? (graph [1 2] 4)
                   (nodes-filtered-by #{1 2 4} g))

        true (eql? (digraph [1 2] 4)
                   (nodes-filtered-by #{1 2 4} dg))))

    (testing "edges filtered"
      (are [expected got] (= expected got)
        true (eql? (graph 1 2 3 4)
                   (edges-filtered-by #(= nil %) g))
        true (eql? (graph [1 2] [1 3] 4)
                   (edges-filtered-by #(= 1 (first %)) g))

        true (eql? (digraph [1 2] 3 4)
                   (edges-filtered-by #{[1 2] [2 4]} dg))))

    (testing "subgraph from start node"
      (are [expected got] (= expected got)
        true (eql? (graph [1 2] [1 3] [2 3])
                   (subgraph-reachable-from g 2))
        true (eql? (graph 4)
                   (subgraph-reachable-from g 4))
        true (eql? (digraph [2 3])
                   (subgraph-reachable-from dg 2))))))

(deftest bipartite-subgraph-test
  (let [dg (digraph [1 2] [2 3] [4 5] [5 6] [3 4] [2 4] [1 6])
        ug (graph dg)]
   (testing "bipartite subgraph"
     (are [expected got] (= expected got)
       '([1 6] [2 4] [3 4]) (sort (edges (bipartite-subgraph dg [1 2 3])))
       '([5 6]) (edges (bipartite-subgraph dg [4 5]))
       true (eql? (graph [2 4] [3 4] [5 6])
                  (bipartite-subgraph ug [4 5]))))))
