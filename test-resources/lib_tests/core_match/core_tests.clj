;;   Copyright (c) Rich Hickey. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns core-match.core-tests
  (:refer-clojure :exclude [compile])
  (:require [clojure.core.match :as match :refer [match defpred]]
            [clojure.test :as t :refer [deftest is]])
  #_(:use clojure.core.match
        clojure.core.match.array
        clojure.core.match.debug
        clojure.core.match.protocols
        clojure.core.match.regex)
  #_(:use [clojure.test]))

(set! *warn-on-reflection* true)

(deftest pattern-match-1
  (is (= (let [x true
               y true
               z true]
           (match [x y z]
             [_ false true] 1
             [false true _ ] 2
             [_ _ false] 3
             [_ _ true] 4
             :else 5))
        4)))

(deftest pattern-match-recur-1
  (is (= ((fn [x y z done]
            (if (not done)
              (match [x y z]
                [_ false true] (recur x y z 1)
                [false true _ ] (recur x y z 2)
                [_ _ false] (recur x y z 3)
                [_ _ true] (recur x y z 4)
                :else 5)
              done)) true true true false)
        4)))

(deftest pattern-match-bind-1
  (is (= (let [x 1 y 2 z 4]
           (match [x y z]
             [1 2 b] [:a0 b]
             [a 2 4] [:a1 a]
             :else []))
        [:a0 4])))

(deftest seq-pattern-match-1
  (is (= (let [x [1]]
           (match [x]
             [1] 1
             [([1] :seq)] 2
             :else []))
        2)))

(deftest seq-pattern-match-2
  (is (= (let [x [1 2 nil nil nil]]
           (match [x]
             [([1] :seq)]     :a0
             [([1 2] :seq)]   :a1
             [([1 2 nil nil nil] :seq)] :a2
             :else []))
        :a2)))

(deftest seq-pattern-match-bind-1
  (is (= (let [x '(1 2 4)
               y nil
               z nil]
           (match [x y z]
             [([1 2 b] :seq) _ _] [:a0 b]
             [([a 2 4] :seq) _ _] [:a1 a]
             :else []))
        [:a0 4])))

(deftest seq-pattern-match-wildcard-row
  (is (= (let [x '(1 2 3)]
           (match [x]
             [([1 z 4] :seq)] z
             [([_ _ _] :seq)] :a2
             :else [])
           :a2))))

;; TODO: depends on val-at*
(deftest map-pattern-match-1
  (is (= (let [x {:a 1 :b 1}]
           (match [x]
             [{:a _ :b 2}] :a0
             [{:a 1 :b 1}] :a1
             [{:c 3 :d _ :e 4}] :a2
             :else nil))
        :a1))
  (is (= (let [x {:a 1 :b 2}]
           (match [x]
             [{:a _ :b 2}] :a0
             [{:a 1 :b 1}] :a1
             [{:c 3 :d _ :e 4}] :a2
             :else nil))
        :a0))
  (is (= (let [x {:c 3 :d 9 :e 4}]
           (match [x]
             [{:a _ :b 2}] :a0
             [{:a 1 :b 1}] :a1
             [{:c 3 :d _ :e 4}] :a2
             :else nil))
        :a2))
  (is (= (let [x {:c 3 :e 4}]
           (match [x]
             [{:a _ :b 2}] :a0
             [{:a 1 :b 1}] :a1
             [{:c 3 :d _ :e 4}] :a2
             :else nil))
        nil)))

(deftest map-pattern-match-2
  (is (= (let [x {:a 1 :b 1}]
           (match [x]
             [{:a _ :b 1}] :a0
             [{:a 1 :b _}] :a1
             [{:c 3 :d _ :e 4}] :a2
             :else []))
        :a0)))

(deftest map-pattern-match-3
  (is (= (let [x {:a 1 :b 1 :c 1}]
           (match [x]
             [{:a _ :b 2}] :a0
             [{:a 1 :b _}] :a1
             [{:c 3 :d _ :e 4}] :a2
             :else []))
        :a1)))

(deftest map-pattern-match-4
  (is (= (let [x {:a 1 :b 1}]
           (match [x]
             [{:a _ :b 2}] :a0
             [{:a _ :b _}] :a1
             [{:c 3 :d _ :e 4}] :a2
             :else []))
        :a1)))

(deftest map-pattern-match-5
  (is (= (let [x {:a 1}]
           (match [x]
             [{:a 1 :b 1}] :a0
             [{:a _ :b _}] :a1
             [{:c 3 :d _ :e 4}] :a2
             :else []))
        [])))

(deftest map-pattern-match-6
  (is (= (let [x {:a 1 :b 1}]
           (match [x]
             [{:b 1}] :a0
             [{:a _ :b _}] :a1
             [{:a _ :b _}] :a2
             :else []))
        :a0)))

(deftest map-pattern-match-7
  (is (= (let [x {:a 1 :b 1}]
           (match [x]
             [{}] :a0
             [{:a _ :b _}] :a1
             [{:a 1 :b 1}] :a2
             :else []))
        :a0)))

(deftest map-pattern-match-8
  (is (= (let [x {:a 1 :b 1}]
           (match [x]
             [{:x nil :y nil}] :a0
             [{:a _ :b _}] :a1
             [{:a 1 :b 1}] :a2
             :else []))
        :a1)))


(deftest map-pattern-match-only-1
  ;; TODO: fails
  #_(is (= (let [x {:a 1 :b 2}]
           (match [x]
             [({:a _ :b 2} :only [:a :b])] :a0
             [{:a 1 :c _}] :a1
             [{:c 3 :d _ :e 4}] :a2
             :else []))
        :a0))
  (is (= (let [x {:a 1 :b 2 :c 3}]
           (match [x]
             [({:a _ :b 2} :only [:a :b])] :a0
             [{:a 1 :c _}] :a1
             [{:c 3 :d _ :e 4}] :a2
             :else []))
        :a1)))

(deftest map-pattern-match-bind-1
  (is (= (let [x {:a 1 :b 2}]
           (match [x]
             [{:a a :b b}] [:a0 a b]
             :else []))
        [:a0 1 2])))

(deftest seq-pattern-match-empty-1
  (is (= (let [x '()]
           (match [x]
             [([] :seq)] :a0
             [([1 & r] :seq)] [:a1 r]
             :else []))
        :a0)))

(deftest seq-pattern-match-rest-1
  (is (= (let [x '(1 2)]
           (match [x]
             [([1] :seq)] :a0
             [([1 & r] :seq)] [:a1 r]
             :else []))
        [:a1 '(2)])))

;; ;; FIXME: stack overflow if vector pattern - David

(deftest seq-pattern-match-rest-2
  (is (= (let [x '(1 2 3 4)]
           (match [x]
             [([1] :seq)] :a0
             [([_ 2 & ([a & b] :seq)] :seq)] [:a1 a b]
             :else []))
        [:a1 3 '(4)])))

(deftest or-pattern-match-1
  (is (= (let [x 4 y 6 z 9]
           (match [x y z]
             [(:or 1 2 3) _ _] :a0
             [4 (:or 5 6 7) _] :a1
             :else []))
        :a1)))

(deftest or-pattern-match-seq-1
  (is (= (let [x '(1 2 3)
               y nil
               z nil]
           (match [x y z]
             [([1 (:or 3 4) 3] :seq) _ _] :a0
             [([1 (:or 2 3) 3] :seq) _ _] :a1
             :else []))
        :a1)))

(deftest or-pattern-match-map-2
  (is (= (let [x {:a 3}
               y nil
               z nil]
           (match [x y z]
             [{:a (:or 1 2)} _ _] :a0
             [{:a (:or 3 4)} _ _] :a1
             :else []))
        :a1)))

(defn div3? [n]
  (= (mod n 3) 0))

(defpred even?)
(defpred odd?)
(defpred div3?)

(deftest guard-pattern-match-1
  (is (= (let [y '(2 3 4 5)]
           (match [y]
             [([_ (a :when even?) _ _] :seq)] :a0
             [([_ (b :when [odd? div3?]) _ _] :seq)] :a1
             :else []))
        :a1)))

;; like guard-pattern-match-1 but uses 'flattened' syntax for guard
(deftest guard-pattern-match-2
  (is (= (let [y '(2 3 4 5)]
           (match [y]
             [([_ a :when even? _ _] :seq)] :a0
             [([_ b :when [odd? div3?] _ _] :seq)] :a1
             :else []))
        :a1)))

;; uses 'flattened' syntax for guard
(deftest guard-pattern-match-3
  (is (= (let [x 2 y 3 z [4 5]]
           (match [x y z]
             [a :when even? _ [b c] :as d] (+ (first d) c)
             [_ b :when [odd? div3?] _] :a1
             :else []))
        9)))

(deftest guard-pattern-match-4
  (is (= (match [1 2]
           [(a :guard #(odd? %)) (b :when odd?)] :a1
           [(a :guard #(odd? %)) _] :a2
           [_ (b :when even?)] :a3
           :else :a4)
        :a2)))

(deftest guard-pattern-match-5
  (is (=
        (let [oddp odd?]
          (match [1 2]
            [a :guard odd? b :when odd?] :a1
            [a :guard oddp _] :a2
            [_ b :when even?] :a3
            :else :a4))
        :a2)))

(deftest unequal-equal-tests
  (is (=
        (match ["foo" "bar"]
          [#".*" #"baz"] :a1
          [#"foo" _] :a2
          [_ "bar"] :a3
          :else :a4)
        :a2)))

(deftest unequal-equal-tests-2
  (is (=
        (let [a 1 b 1]
          (match [1 2]
            [a 3] :a1
            [1 2] :a2
            [2 _] :a5
            [_ 3] :a4
            :else :a3))
        :a2)))

;; use ':when pattern to match literal :when (as opposed to guard syntax)
(deftest literal-when-match-1
  (is (= (let [x :as y :when z 1]
           (match [x y z]
             [a ':when 1] :success
             [:as _ 2] :fail
             :else :fail))
        :success)))

(deftest same-symbol-using-guards
  (is (=  (let [e '(+ 1 (+ 2 3))
                op (first e)
                op? #(= % op)]
            (match [e]
              [([p :guard op? x ([p2 :guard op? y z] :seq)] :seq)] (list p x y z)))
        '(+ 1 2 3))))

(deftest quoted-symbol
  (is (=  (let [e '(+ 1 (+ 2 3))]
            (match [e]
              [(['+ x (['+ y z] :seq)] :seq)] (list '+ x y z)))
        '(+ 1 2 3))))

(deftest literal-quote
  (is (=  (let [e 'quote
                f 10]
            (match [e f]
              ['quote quote] quote))
        10)))


(deftest literal-quote-seq
  (is (=  (let [e '(:a (quote 10))]
            (match [e]
              [([quote (['quote 10] :seq)] :seq)] quote))
        :a)))



;; (extend-type java.util.Date
;;   IMatchLookup
;;   (val-at [this k not-found]
;;     (case k
;;       :year    (.getYear this)
;;       :month   (.getMonth this)
;;       :date    (.getDate this)
;;       :hours   (.getHours this)
;;       :minutes (.getMinutes this)
;;       not-found)))

;; (deftest map-pattern-interop-1
;;   (is (= (let [d (java.util.Date. 2010 10 1 12 30)]
;;            (matchm [d]
;;              [{:year 2009 :month a}] [:a0 a]
;;              [{:year (:or 2010 2011) :month b}] [:a1 b]
;;              :else []))
;;         [:a1 10])))

(deftest map-pattern-ocr-order-1
  (is (= (let [v [{:a 1} 2]]
           (match [v]
             [[{:a 2} 2]] :a0
             [[{:a _} 2]] :a1
             :else []))
        :a1)))

(deftest as-pattern-match-1
  (is (= (let [v [[1 2]]]
           (match [v]
             [([3 1] :seq)] :a0
             [([(([1 a] :seq) :as b)] :seq)] [:a1 a b]
             :else []))
        [:a1 2 [1 2]])))

(deftest else-clause-1
  (is (= (let [v [1]]
           (match [v]
             [2] 1
             :else 21))
        21)))

(deftest else-clause-seq-pattern-1
  (is (= (let [v [[1 2]]]
           (match [v]
             [([1 3] :seq)] 1
             :else 21))
        21)))

(deftest else-clause-map-pattern-1
  (is (= (let [v {:a 1}]
           (match [v]
             [{:a a}] 1
             :else 21))
        1)))

(deftest else-clause-guard-pattern-1
  (is (= (let [v 1]
           (match [v]
             [(_ :when even?)] 1
             :else 21))
        21)))

(deftest else-clause-or-pattern-1
  (is (= (let [v 3]
           (match [v]
             [(:or 1  2)] :a0
             :else :a1))
        :a1)))

(deftest match-expr-1
  (is (= (->> (range 1 16)
           (map (fn [x]
                  (match [(mod x 3) (mod x 5)]
                    [0 0] "FizzBuzz"
                    [0 _] "Fizz"
                    [_ 0] "Buzz"
                    :else (str x)))))
        '("1" "2" "Fizz" "4" "Buzz" "Fizz" "7" "8" "Fizz" "Buzz" "11" "Fizz" "13" "14" "FizzBuzz"))))

(deftest match-single-1
  (is (= (let [x 3]
           (match x
             1 :a0
             2 :a1
             :else :a2))
        :a2)))

(deftest match-single-2
  (is (= (let [x 3]
           (match (mod x 2)
             1 :a0
             2 :a1
             :else :a2))
        :a0)))

;; TODO: this needs to wait for backtracking. GuardPatterns need to be grouped w/
;; whatever pattern they actually contain - David
(comment
  (deftest match-single-3
    (is (= (let [x [1 2]]
             (match x
               [2 1] :a0 
               (_ :when #(= (count %) 2)) :a1
               :else :a2))
          :a1)))
  )

(deftest match-local-1
  (is (= (let [x 2
               y 2]
           (match [x]
             [0] :a0
             [1] :a1
             [y] :a2
             :else :a3))
        :a2)))

(deftest match-local-2
  (is (= (let [x 2]
           (match [x]
             [0] :a0
             [1] :a1
             [2] :a2
             :else :a3))
        :a2)))

(deftest match-local-3
  (is (= (let [a 1]
           (match [1 2]
             [1 3] :a0
             [a 2] :a1
             :else :a2))
        :a1)))

(deftest basic-regex
  (is (= (match ["asdf"]
           [#"asdf"] 1
           :else 2)
        1)))

(deftest test-false-expr-works-1
  (is (= (match [true false]
           [true false] 1
           [false true] 2
           :else (throw (Exception. "Shouldn't be here")))
        1)))

(deftest test-lazy-source-case-1
  (is (= (let [x [1 2]]
           (match [x] 
             [(:or [1 2] [3 4] [5 6] [7 8] [9 10])] :a0
             :else (throw (Exception. "Shouldn't be here"))))
        :a0)))

(deftest test-wildcard-local-1
  (is (= (let [_ 1
               x 2
               y 3]
           (match [x y]
             [1 1] :a0
             [_ 2] :a1
             [2 3] :a2
             :else :a3))
        :a2)))

(deftest vector-pattern-match-1
  (is (= (let [x [1 2 3]]
           (match [x]
             [([_ _ 2] :clojure.core.match/vector)] :a0
             [([1 1 3] :clojure.core.match/vector)] :a1
             [([1 2 3] :clojure.core.match/vector)] :a2
             :else :a3))
        :a2)))

(deftest red-black-tree-pattern-1
  (is (= (let [n [:black [:red [:red 1 2 3] 3 4] 5 6]]
           (match [n]
             [(:or [:black [:red [:red a x b] y c] z d]
                   [:black [:red a x [:red b y c]] z d]
                   [:black a x [:red [:red b y c] z d]]
                   [:black a x [:red b y [:red c z d]]])] :balance
             :else :balanced))
        :balance))
  (is (= (let [n [:black [:red 1 2 [:red 3 4 5]] 6 7]]
           (match [n]
             [(:or [:black [:red [:red a x b] y c] z d]
                   [:black [:red a x [:red b y c]] z d]
                   [:black a x [:red [:red b y c] z d]]
                   [:black a x [:red b y [:red c z d]]])] :balance
             :else :balanced))
        :balance))
  (is (= (let [n [:black 1 2 [:red [:red 3 4 5] 6 7]]]
           (match [n]
             [(:or [:black [:red [:red a x b] y c] z d]
                   [:black [:red a x [:red b y c]] z d]
                   [:black a x [:red [:red b y c] z d]]
                   [:black a x [:red b y [:red c z d]]])] :balance
             :else :balanced))
        :balance))
  (is (= (let [n [:black 1 2 [:red 3 4 [:red 5 6 7]]]]
           (match [n]
             [(:or [:black [:red [:red a x b] y c] z d]
                   [:black [:red a x [:red b y c]] z d]
                   [:black a x [:red [:red b y c] z d]]
                   [:black a x [:red b y [:red c z d]]])] :balance
             :else :balanced))
        :balance))
  (is (= (let [n [:black 1 [:red 2 3 [:red 4 5 6]] 7]]
           (match [n]
             [(:or [:black [:red [:red a x b] y c] z d]
                   [:black [:red a x [:red b y c]] z d]
                   [:black a x [:red [:red b y c] z d]]
                   [:black a x [:red b y [:red c z d]]])] :balance
             :else :balanced))
        :balanced)))

(deftest vector-pattern-int-array-1
  (is (= (let [x (int-array [1 2 3])]
           (match [^ints x]
             [[_ _ 2]] :a0
             [[1 1 3]] :a1
             [[1 2 3]] :a2
             :else :a3))
          :a2)))

(deftest vector-pattern-object-array-1
  (is (= (let [x (object-array [:foo :bar :baz])]
           (match [^objects x]
             [[_ _ :bar]] :a0
             [[:foo :foo :bar]] :a1
             [[:foo :bar :baz]] :a2
             :else :a3))
          :a2)))

(deftest vector-pattern-rest-1
  (is (= (let [v [1 2 3 4]]
           (match [v]
             [([1 1 3 & r] :clojure.core.match/vector)] :a0
             [([1 2 4 & r] :clojure.core.match/vector)] :a1
             [([1 2 3 & r] :clojure.core.match/vector)] :a2
             :else :a3))
        :a2)))

(deftest vector-pattern-rest-2
  (is (= (let [v [1 2 3 4]]
           (let [v [1 2 3 4]]
             (match [v]
               [([1 1 3 & r] :clojure.core.match/vector)] :a0
               [([1 2 & r] :clojure.core.match/vector)] :a1
               :else :a3)))
        :a1)))

(deftest vector-bind-1
  (is (= (let [node 1]
           (match [node]
             [[1]] :a0
             [a] a
             :else :a1))
        1)))

(deftest empty-vector-1
  (is (= (let [v []]
           (match [v]
             [[]] 1
             :else 2))
        1)))

(deftest empty-vector-2
  (is (= (let [v [1 2]]
           (match [v]
             [[]] :a0
             [[x & r]] :a1
             :else :a2))
        :a1)))

(deftest vector-pattern-length-1
  (is (= (let [v [[1 2]]]
           (match [v]
             [[3 1]] :a0
             [[([1 a] :as b)]] [:a1 a b]
             :else :a2))
        [:a1 2 [1 2]])))

(deftest seq-infer-rest-1
  (is (= (let [l '(1 2 3)]
           (match [l]
             [([a & [b & [c]]] :seq)] :a0
             :else :a1))
        :a0)))

(deftest vector-offset-1
  (is (= (match [[:pow :x 2]]
           [[:pow arg pow]] 0
           [[:mult & args]] 1
           :else 2)
        0)))

(deftest match-expr-2
  (is (= (match [false]
           [false] true)
        true)))

(deftest vector-rest-pattern-1
  (is (= (match [[:plus 1 2 3]]
           [[:pow arg pow]] 0
           [[:plus & args]] 1
           :else 2)
         1)))

(macroexpand '(match [x]
                     [({:a _ :b _ :c _ :d _} :only [:a :b :c :d])] :a-1
                     [({:a _ :b 2} :only [:a :b])] :a0
                     [{:a 1 :c _}] :a1
                     [{:c 3 :d _ :e 4}] :a2
                     :else []))

(deftest map-pattern-match-only-2
  (is (= (let [x {:a 1 :b 2 :c 10 :d 30}]
           (match [x]
             [({:a _ :b _ :c _ :d _} :only [:a :b :c :d])] :a-1
             [({:a _ :b 2} :only [:a :b])] :a0
             [{:a 1 :c _}] :a1
             [{:c 3 :d _ :e 4}] :a2
             :else []))
        :a-1)))

;; FAIL
#_(deftest map-pattern-match-only-3
  (is (and (= (let [m {:a 1}]
                (match [m]
                  [({:a 1} :only [:a])] :a0
                  :else :a1))
             :a0)
        (= (let [m {:a 1 :b 2}]
             (match [m]
               [({:a 1} :only [:a])] :a0
               :else :a1))
          :a1))))

(deftest map-pattern-heterogenous-keys-1
  (is (= (let [m {:foo 1 "bar" 2}]
           (match [m]
             [{:foo 1 "bar" 2}] :a0
             :else :a1))
        :a0)))

(deftest exception-1
  (is (= (try
           (match :a :a (throw (Exception.)) :else :c)
           (catch Exception e
             :d))
        :d)))

(deftest match-order-1
  (is (= (let [x '(1 2) y 1]
           (match [x y]
             [([1] :seq) _] :a0
             [_ 1] :a1
             [([1 2] :seq) _] :a2
             [_ 2] :a3
             :else :a4))
        :a1))
  (is (= (let [x '(1 2) y 1]
           (match [x y]
             [([1] :seq) _] :a0
             [([1 2] :seq) _] :a2
             [_ 1] :a1
             [_ 2] :a3
             :else :a4))
        :a2)))

(deftest match-order-2
  (is (= (let [x '(1 2) y 3]
           (match [x y]
             [([1] :seq) _] :a0
             [_ 1] :a1
             [([1 2] :seq) _] :a2
             [_ 2] :a3
             :else :a4))
        :a2)))

(deftest match-order-3
  (is (= (let [x '(1) y 3]
           (match [x y]
             [([1] :seq) _] :a0
             [_ 1] :a1
             [([1 2] :seq) _] :a2
             [_ 2] :a3
             :else :a4))
        :a0)))

(deftest match-order-4
  (is (= (let [x '(1 2 3) y 2]
           (match [x y]
             [([1] :seq) _] :a0
             [_ 1] :a1
             [([1 2] :seq) _] :a2
             [_ 2] :a3
             :else :a4))
        :a3)))

(deftest match-order-5
  (is (= (match [["foo"]]
           [["foo"]] :a0
           [["foo" a]] :a1
           [["baz"]] :a2
           [["baz" a b]] :a3
           :else :a4)
        :a0)))

(deftest match-order-6
  (is (= (match [[2]]
           [[1]] :a0
           [1] :a1
           [[2]] :a2
           [2] :a3
           :else :a4)
        :a2)))

(deftest match-order-6-recur
  (is (= ((fn [x done]
            (if done
              done
              (match [x]
                [[1]] (recur x :a0)
                [1] (recur x :a1)
                [[2]] (recur x :a2)
                [2] (recur x :a3)
                :else :a4))) [2] false)
        :a2))
  (is (= ((fn [x done]
            (if done
              done
              (match [x]
                [[1]] (recur x :a0)
                [1] (recur x :a1)
                [[2]] (recur x :a2)
                [2] (recur x :a3)
                [3] (recur x :a4)
                [[3]] (recur x :a4)
                :else :a5))) [3] false)
        :a4)))

(deftest match-order-7
  (is (= (match [[2]]
           [1] :a0
           [[1]] :a1
           [2] :a2
           [[2]] :a3
           :else :a4)
        :a3)))

(deftest match-order-8
  (is (= (let [xs [:c]]
           (match xs
             [:a] :a0
             [:b b] :a1
             [:c] :a2
             :else :a3))
         :a2)))

(deftest match-order-9
  (is (= (let [xs [1 2 3]]
           (match [xs]
             [([1 2 4] :seq)] :a0
             [[1 2 5]] :a1
             [([1 2 6] :seq)] :a2
             [[1 2 3]] :a3))
        :a3)))

(deftest match-app-1
  (let [n 1]
    (is (= (match [n]
             [(1 :<< inc)] :one
             [(2 :<< inc)] :two
             :else :oops)
          :two))
    (is (= (match [n]
             [(1 :<< inc)] :one
             [(3 :<< #(* % 3))] :three
             :else :oops)
          :three))))

(deftest match-app-2
  (let [v [1 2 4 3]]
    (is (= (match [v]
             [(([1 2 4 5] :seq) :<< sort)] :this-sort
             [(([3 _ _ _] :seq) :<< reverse)] :last-is-three
             :else :oops)
          :last-is-three))
    (is (= (match [v]
             [((:or 3 4) :<< count)] :three-or-four
             [(5 :<< count)] :five
             :else :oops)
          :three-or-four))))

(deftest match-app-3
  (let [v [1 2 3]
        m {:a 2 :b 2}]
    (is (= (match [v]
             [[1 (3 :<< inc) 3]] :match1
             [[1 (4 :<< inc) 3]] :match2
             :else :no-match)
          :match1))
    (is (= (match [m]
             [{:a (2 :<< inc) :b _}] :match1
             [{:a (3 :<< inc) :b _}] :match2
             :else :no-match)
           :match2))))

;; =============================================================================
;; Tickets

(deftest match-66
  (is (= (match 3 x x) 3))
  (is (= (match 'my-sym a a) 'my-sym)))

(deftest match-70
  (is (= (let [xqq {:cz 1 :dz 2}]
           (match [xqq]
             [{:z a :zz b}] [:a0 a b]
             [{:cz a :dz b}] [:a2 a b]
             :else []))
        [:a2 1 2]))
  (is (= (let [xmm {:bz 2}]
           (match [xmm]
             [{:az a}] [:a0 a]
             [{:bz b}] [:a1 b]
             :else []))
        [:a1 2])))

(deftest match-51
  (is (= (match (vector)
           ([(re :guard string?)] :seq) 4
           [] 6)
         6)))

(deftest match-55
  (is (= (match [ [1 2] ] [([& _] :seq)] true)
         true)))

(deftest match-56
  (is (= (let [x []]
           (match [x]
             [[h & t]] [h t]
             :else :nomatch))
        :nomatch))
  (is (= (let [x [1]]
           (match [x]
             [[h & t]] [h t]
             :else :nomatch))
          [1 []])))

(deftest match-68
  (is (= (match [[:x]]
           [[m n & _]] 1
           :else nil)
          nil)))

(deftest match-35
  (is (= (let [l '(1 2 3)]
           (match [l]
             [([a & [b & [c d]]] :seq)] :a0
             :else :a1))
         :a1))
  (is (= (let [x ()]
           (match [x]
             [([h & t] :seq)] [h t]
             [_] :a1))
         :a1)))

(deftest match-61
  (is (= (let [q '(a) y '(b) z '(c)]
           (match [q (seq y) z]
             [([_] :seq) _ _] 'a
             [_ _ _] 'b))
         'a)))

(deftest match-80
  (is (= (match [:r :d]
           [:s :d] nil
           [:r :t] nil
           [:r :d] :x
           [:s :t] nil)
        :x))
  (is (= (match [:r :d]
           [:r :t] nil
           [:s :d] nil
           [:r :d] :x
           [:s :t] nil)
        :x)))

(deftest match-83
  (is (= (let [x [1 2]]
           (match x 
             [0 _ _ _] :a 
             [1 & _] :b 
             _ :c))
        :b)))

(deftest match-84
  (is (= (let [v [3 2 3 4]]
           (match [v]
             [[1 1 3]] :a0
             [[3 & r]] :a2))
        :a2)))

(deftest match-92
  (is (= (let [m {:a.b 1}]
           (match [m]
             [{:a.b _}] :a0))
         :a0)))
