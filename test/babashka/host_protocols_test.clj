(ns babashka.host-protocols-test
  (:require [babashka.test-utils :as tu]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]))

(defn bb [& args]
  (edn/read-string (apply tu/bb nil (map str args))))

(deftest io-factory-test
  (testing "a record implementing IOFactory works with every built-in IO fn"
    (is (= ["hello" "hello" true]
           (bb "
(require '[clojure.java.io :as io])
(defrecord Src [s]
  io/IOFactory
  (make-input-stream [_ _] (java.io.ByteArrayInputStream. (.getBytes s))))
(def src (->Src \"hello\"))
[(slurp src)
 (with-open [r (io/reader src)] (first (line-seq r)))
 (satisfies? io/IOFactory src)]"))))
  (testing "reify"
    (is (= "hi" (bb "
(require '[clojure.java.io :as io])
(slurp (reify io/IOFactory (make-reader [_ _] (java.io.StringReader. \"hi\"))))")))))

(deftest coll-reduce-test
  (is (= [6 [0 1 2] 6]
         (bb "
(require '[clojure.core.protocols :as p])
(defrecord Nums [n]
  p/CollReduce
  (coll-reduce [_ f] (reduce f (range n)))
  (coll-reduce [_ f init] (reduce f init (range n))))
[(reduce + (->Nums 4)) (into [] (->Nums 3)) (transduce (map inc) + (->Nums 3))]"))))

(deftest kv-reduce-test
  (is (= {:a 2 :b 3}
         (bb "
(require '[clojure.core.protocols :as p])
(deftype Pairs []
  p/IKVReduce
  (kv-reduce [_ f init] (f (f init :a 1) :b 2)))
(reduce-kv (fn [acc k v] (assoc acc k (inc v))) {} (->Pairs))"))))

(deftest coercions-test
  (testing "a record as a file"
    (is (= "/tmp/a/b" (bb "
(require '[clojure.java.io :as io])
(defrecord Loc [p] io/Coercions (as-file [_] (io/file p)) (as-url [_] nil))
(str (io/file (->Loc \"/tmp/a\") \"b\"))"))))
  (testing "a host class, java.nio.file.Path"
    (is (= "a/b" (bb "
(require '[clojure.java.io :as io])
(extend-protocol io/Coercions
  java.nio.file.Path
  (as-file [p] (.toFile p))
  (as-url [p] (.toURL (.toUri p))))
(str (io/file (java.nio.file.Paths/get \"a\" (into-array String [\"b\"]))))")))))

(deftest datafiable-test
  (testing "a record and a map-backed deftype"
    (is (= [{:r 1} {:m 2} true true]
           (bb "
(require '[clojure.datafy :as d] '[clojure.core.protocols :as p])
(defrecord R [x] p/Datafiable (datafy [_] {:r x}))
(deftype M [x]
  clojure.lang.IPersistentMap
  p/Datafiable
  (datafy [_] {:m x}))
(defrecord Plain [])
[(d/datafy (->R 1)) (d/datafy (->M 2)) (satisfies? p/Datafiable (->R 1)) (satisfies? p/Datafiable (->Plain))]"
               ))))
  (testing "extend-type on a sci record after construction"
    (is (= [:plain {:x 1}]
           (bb "
(require '[clojure.datafy :as d] '[clojure.core.protocols :as p])
(defrecord Plain [x])
(def p (->Plain 1))
(extend-type Plain p/Datafiable (datafy [_] :plain))
[(d/datafy p) (d/datafy (with-meta {:x 1} {}))]")))))
