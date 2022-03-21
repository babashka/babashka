(ns loom.test.flow
  (:require [loom.graph :refer (weighted-digraph successors predecessors weight)]
            [loom.flow :refer (edmonds-karp is-admissible-flow?)]
            [loom.alg :refer [max-flow]]
            #?@(:clj [[clojure.test :refer :all]]
                :cljs [cljs.test]))
  #?@(:cljs [(:require-macros [cljs.test :refer (deftest testing are is)])]))


;; Trivial case
(def g0
  (weighted-digraph
   [:s :t 100]))

;; From Cormen et al. Algorithms, 3 ed. p. 726-727
(def g1
  (weighted-digraph
   [:s :v1 16]
   [:s :v2 13]
   [:v1 :v3 12]
   [:v2 :v1 4]
   [:v2 :v4 14]
   [:v3 :v2 9]
   [:v3 :t 20]
   [:v4 :v3 7]
   [:v4 :t 4]))

;; Source and sink disconnected
(def g2
  (weighted-digraph
   [:s :a 5]
   [:b :t 10]))


(deftest edmonds-karp-test
  (are [max-value network]
       (let [[flow value] (edmonds-karp (successors network)
                                        (predecessors network)
                                        (weight network)
                                        :s :t)]
         (and (= max-value value)
              (is-admissible-flow? flow (weight network)
                                   :s :t)))
       23 g1
       100 g0
       0 g2))


(deftest max-flow-convenience-test
  (are [max-value network]
       (let [[flow value] (max-flow (weighted-digraph network) :s :t)]
         (and (= max-value value)
              (is-admissible-flow? flow (weight network) :s :t)))
       23 g1))
