(ns loom.test.attr
  (:require [loom.graph :refer (digraph)]
            [loom.attr :refer (add-attr attr add-attr-to-nodes add-attr-to-edges)]
            #?@(:clj [[clojure.test :refer :all]]))
  #?@(:cljs [(:require-macros [cljs.test :refer (deftest testing are is)])]))

(deftest attr-graph-test
  (let [g (digraph [1 2] [2 3] [2 4] [3 5] [4 5])
        lg1 (-> g
                (add-attr 1 :label "node label")
                (add-attr 2 3 :label "edge label"))
        lg2 (-> g
                (add-attr-to-nodes
                 :label "node odd" [1 3 5])
                (add-attr-to-nodes
                 :label "node even" [2 4])
                (add-attr-to-edges
                 :label "edge from node 2" [[2 3] [2 4]])
                (add-attr-to-edges
                 :label "edge to node 5" [[3 5] [4 5]]))]
    (is (= "node label" (attr lg1 1 :label)))
    (is (= "edge label" (attr lg1 2 3 :label)))
    (is (= "node odd" (attr lg2 1 :label)))
    (is (= "node odd" (attr lg2 3 :label)))
    (is (= "node odd" (attr lg2 5 :label)))
    (is (= "node even" (attr lg2 2 :label)))
    (is (= "node even" (attr lg2 4 :label)))
    (is (= "edge from node 2" (attr lg2 2 3 :label)))
    (is (= "edge from node 2" (attr lg2 2 4 :label)))
    (is (= "edge to node 5" (attr lg2 3 5 :label)))
    (is (= "edge to node 5" (attr lg2 4 5 :label)))))
