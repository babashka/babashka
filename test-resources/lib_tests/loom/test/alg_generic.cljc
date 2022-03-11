(ns loom.test.alg-generic
  (:require [loom.alg-generic :as lag]
            [loom.graph :as g]
            [clojure.set :as set]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            #?@(:clj [[clojure.test :refer :all]
                      [clojure.test.check.clojure-test :refer [defspec]]
                      [clojure.test.check.properties :as prop]]
                :cljs [clojure.test.check.properties]))
  #?@(:cljs [(:require-macros [cljs.test :refer (deftest testing are is)]
                              [clojure.test.check.clojure-test :refer [defspec]]
                              [clojure.test.check.properties :as prop])]))

(defn dag-samples-gen
  [dag percent]
  (let [dag-size (count dag)
        sample-count (int (* percent dag-size))]
    (gen/bind (apply gen/tuple
                     ;; May collide but this is fine.
                     (repeat (* 2 sample-count) (gen/choose 0 dag-size)))
              (fn [samples]
                (gen/tuple (gen/return dag) (gen/return samples))))))

(defn gen-dag
  ([] (gen-dag [#{}] 10))
  ([nodes] (gen-dag [#{}] nodes))
  ([dag-so-far nodes]
     (gen/bind (gen/frequency [[80 (gen/return 1)]
                               [19 (gen/return 2)]
                               [1 (gen/return 0)]])
               (fn [parent-count]
                 (gen/bind (gen/such-that
                            (fn [& parents]
                              (when (not (empty? parents))
                                (apply distinct? parents)))
                            (apply gen/tuple
                                   (repeat (min (count dag-so-far) parent-count)
                                           (gen/choose 0 (dec (count dag-so-far))))))
                           (fn [parents]
                             (if (< 0 nodes)
                               (gen-dag (conj dag-so-far (set parents))
                                        (dec nodes))
                               (dag-samples-gen dag-so-far 0.5))))))))


(defn anc-model-new [] {})

(defn anc-model-add
  [anc-model node & parents]
  (let [ancs (reduce set/union
                     (map #(get anc-model %)
                          parents))
        ancs (into ancs parents)
        ancs (disj ancs nil)]
    (assoc anc-model node ancs)))

(defn anc-model-anc?
  [anc-model childer parenter]
  (boolean
   (get
    (get anc-model childer)
    parenter)))

(defn anc->anc-model
  [ancestry]
  (let [anc-nodes (lag/ancestry-nodes ancestry)]
    (zipmap anc-nodes
            (map #(set (lag/ancestors ancestry %)) anc-nodes))))

(def dag-similarity-props
  (prop/for-all [[dag samples] (gen/bind (gen/choose 0 100)
                                         (fn [dag-size]
                                           (gen-dag dag-size)))]
    (let [anc (reduce (fn [a [i ps]]
                        (apply lag/ancestry-add a i (seq ps)))
                      (lag/ancestry-new)
                      (map-indexed vector dag))
          anc-model (reduce (fn [a [i ps]]
                              (apply anc-model-add a i (seq ps)))
                            (anc-model-new)
                            (map-indexed vector dag))
          samp-pairs (partition 2 samples)
          anc-to-model (anc->anc-model anc)]
      (and
       (= anc-model anc-to-model)
       (every?
        (fn [[a b]]
          (and
           (= (lag/ancestor? anc b a)
              (anc-model-anc? anc-model b a))
           (= (lag/ancestor? anc a b)
              (anc-model-anc? anc-model a b))))
        samp-pairs)))))

(defspec ^:test-check-fast dag-similarity-100
  100
  dag-similarity-props)

(defspec ^:test-check-slow dag-similarity-2000
  2000
  dag-similarity-props)

(def g1
  {:a [:b :c]
   :b [:d]
   :c [:d]
   :d nil})

(def g2
  {:a [:b]
   :b [:a]})

(def g3
  {:a [:b]
   :b [:a :c :d]
   :c [:b :e]
   :d [:b :c :e]
   :e [:c :d :f]
   :f []})

(def g4 ; like g3 with some loops
  {:a [:b]
   :b [:a :c :d]
   :c [:b :c :e]
   :d [:b :c :e]
   :e [:c :d :f]
   :f [:f]})

(def g5 ; like g1 but as an undirected graph
  {:a [:b :c]
   :b [:d :a]
   :c [:a :d]
   :d [:c :b]})

(def g6 ; unconnected with some loops
  {:a [:a]
   :b [:a :c]
   :c [:b :c]
   :d [:e]
   :e [:d :f]
   :f [:f]})

(deftest tracing-paths
  (are [g n p] (= (sort (lag/trace-paths g n)) p)
       {:a nil} :a
       [[:a]]

       {:a #{:b} :b nil} :a
       [[:a :b]]

       g1 :a
       [[:a :b :d] [:a :c :d]]))

(deftest bf-paths-bi-test
  (are [g start end paths] (= (lag/bf-paths-bi g g start end) paths)
    g2 :a :b
    [[:a :b]]

    g3 :a :c
    [[:a :b :c]]

    g3 :a :e
    [[:a :b :c :e] [:a :b :d :e]]))

(deftest edge-traverse
  ; works with nodes without outgoing edges or just a loop to iself
  (are [g start expected] (let [pre (lag/pre-edge-traverse g start)
                                post (lag/post-edge-traverse g start)]
                            (= expected pre (seq (reverse post))))
       g1 :d nil
       
       g4 :f '([:f :f]))
  ; covers the whole graph when it's totally connected from start
  (are [g start expected] (let [pre (lag/pre-edge-traverse g start)
                                post (lag/post-edge-traverse g start)
                                dg (g/digraph g)
                                edges (g/edges dg)]
                            (and
                              (= expected pre (seq (reverse post)))
                              (= (count edges) (count post))
                              (= (set edges) (set post))))
       g1 :a '([:a :b] [:b :d] [:a :c] [:c :d])
       
       g4 :a '([:a :b] [:b :a] [:b :c] [:c :b] [:c :c] [:c :e] [:e :c]
               [:e :d] [:d :b] [:d :c] [:d :e] [:e :f] [:f :f] [:b :d])
       
       g4 :c '([:c :b] [:b :a] [:a :b] [:b :c] [:b :d] [:d :b] [:d :c]
               [:d :e] [:e :c] [:e :d] [:e :f] [:f :f] [:c :c] [:c :e])
       
       g5 :a '([:a :b] [:b :d] [:d :c] [:c :a]
               [:c :d] [:d :b] [:b :a] [:a :c]))
  ; post traversal returning seen nodes allows complete graph coverage
  ; without duplicates when iterating on all nodes of the graph
  (are [g] (let [dg (g/digraph g)
                 edges (g/edges dg)
                 loop-post-traverse
                     (loop [nodes (reverse (g/nodes dg))
                            ; reverse makes this more interesting as graphs
                            ; are often specified in the forward direction
                            seen #{}
                            acc ()]
                       (if-let [node (first nodes)]
                         (let [[edges seen]
                                   (lag/post-edge-traverse
                                     g
                                     node
                                     :seen seen
                                     :return-seen true)]
                           (recur (next nodes)
                                  seen
                                  (concat acc edges)))
                         acc))]
             (and
               (= (count edges) (count loop-post-traverse))
               (= (set edges) (set loop-post-traverse))))
       g1 g2 g3 g4 g5 g6))
