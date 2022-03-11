(ns loom.test.label
  (:require [loom.graph :as g]
            [loom.label :as lbl]
            #?@(:clj [[clojure.test :refer (deftest is)]]))
  #?@(:cljs [(:require-macros [cljs.test :refer (deftest is)])]))

(deftest labeled-graph-test
  (let [g (g/digraph [1 2] [2 3] [2 4] [3 5] [4 5])
        lg1 (-> g
               (lbl/add-label 1 "node label")
               (lbl/add-label 2 3 "edge label"))
        lg2 (-> (g/digraph)
                (lbl/add-labeled-nodes
                 1 "node label 1"
                 2 "node label 2")
                (lbl/add-labeled-edges
                 [1 2] "edge label 1"
                 [2 3] "edge label 2"))]
    (is (= "node label" (lbl/label lg1 1)))
    (is (= "edge label" (lbl/label lg1 2 3)))
    (is (= #{1 2 3} (set (g/nodes lg2))))
    (is (= #{[1 2] [2 3]} (set (g/edges lg2))))
    (is (= "node label 1" (lbl/label lg2 1)))
    (is (= "node label 2" (lbl/label lg2 2)))
    (is (= "edge label 1" (lbl/label lg2 1 2)))
    (is (= "edge label 2" (lbl/label lg2 2 3)))))
