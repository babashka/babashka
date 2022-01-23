;; Test routines for clojure.algo.monads

;; Copyright (c) Konrad Hinsen, 2011. All rights reserved.  The use
;; and distribution terms for this software are covered by the Eclipse
;; Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this
;; distribution.  By using this software in any fashion, you are
;; agreeing to be bound by the terms of this license.  You must not
;; remove this notice, or any other, from this software.

(ns clojure.algo.test-monads
  (:use [clojure.test :only (deftest is are run-tests)]
        [clojure.algo.monads
         :only (with-monad domonad m-lift m-seq m-chain writer-m write
                 sequence-m maybe-m state-m maybe-t sequence-t
                 reader-m ask asks local)]))


(deftest domonad-if-then
  (let [monad-value (domonad maybe-m
                      [ a 5
                        :let [c 7]
                        :if (and (= a 5) (= c 7))
                        :then [
                          b 6
                        ]
                        :else [
                          b nil
                        ]]
                      [a b])]
  (is (= monad-value [5 6]))))

(deftest domonad-nested-if-then
  (let [monad-value (domonad maybe-m
                      [ a 5
                       :if (= a 5)
                        :then [
                          b 6
                          :if (= b 6)
                          :then [
                            c 7
                          ]
                          :else [
                            c nil
                          ]  
                        ]
                        :else [
                          b nil
                          c nil
                        ]]
                      [a b c])]
  (is (= monad-value [5 6 7]))))

(deftest domonad-if-then-with-when
  (let [monad-value (domonad maybe-m
                      [ a 5
                        :when (= a 5)
                        :if (= a 1)
                        :then [
                          b 6]
                        :else [
                          b nil]]
                      [a b])]
  (is (= monad-value nil))))

(deftest domonad-cond
  (let [monad-value (domonad maybe-m
                      [ a 5
                        :when (= a 5)
                        :cond
                          [(< a 1)
                            [result "less than one"]
                           (< a 3)
                            [result "less than three"]
                           (< a 6)
                            [result "less than six"]
                           :else
                            [result "arbitrary number"]]
                        b 7
                        :let [some-val 12345]]
                      [result b some-val])]
  (is (= monad-value ["less than six" 7 12345]))))

(deftest sequence-monad
  (with-monad sequence-m
    (are [a b] (= a b)
      (domonad [x (range 3) y (range 2)] (+ x y))
        '(0 1 1 2 2 3)
      (domonad [x (range 5) y (range (+ 1 x)) :when  (= (+ x y) 2)] (list x y))
        '((1 1) (2 0))
      ((m-lift 2 #(list %1 %2)) (range 3) (range 2))
        '((0 0) (0 1) (1 0) (1 1) (2 0) (2 1))
      (m-seq (repeat 3 (range 2)))
        '((0 0 0) (0 0 1) (0 1 0) (0 1 1) (1 0 0) (1 0 1) (1 1 0) (1 1 1))
      ((m-chain (repeat 3 range)) 5)
        '(0 0 0 1 0 0 1 0 1 2)
      (m-plus (range 3) (range 2))
        '(0 1 2 0 1))))

(deftest maybe-monad
  (with-monad maybe-m
    (let [m+ (m-lift 2 +)
          mdiv (fn [x y] (domonad [a x  b y  :when (not (zero? b))] (/ a b)))]
      (are [a b] (= a b)
        (m+ (m-result 1) (m-result 3))
          (m-result 4)
        (mdiv (m-result 1) (m-result 3))
          (m-result (/ 1 3))
        (m+ 1 (mdiv (m-result 1) (m-result 0)))
          m-zero
        (m-plus m-zero (m-result 1) m-zero (m-result 2))
          (m-result 1)))))

(deftest writer-monad
  (is (= (domonad (writer-m "")
                  [x (m-result 1)
                   _ (write "first step\n")
                   y (m-result 2)
                   _ (write "second step\n")]
                  (+ x y))
         [3 "first step\nsecond step\n"]))
  (is (= (domonad (writer-m [])
                  [_ (write :a)
                   a (m-result 1)
                   _ (write :b)
                   b (m-result 2)]
                  (+ a b))
         [3 [:a :b]]))
  (is (= (domonad (writer-m ())
                  [_ (write :a)
                   a (m-result 1)
                   _ (write :b)
                   b (m-result 2)]
                  (+ a b))
         [3 '(:a :b)]))
  (is (= (domonad (writer-m (list))
                  [_ (write :a)
                   a (m-result 1)
                   _ (write :b)
                   b (m-result 2)]
                  (+ a b))
         [3 (list :a :b)]))
  (is (= (domonad (writer-m #{})
                  [_ (write :a)
                   a (m-result 1)
                   _ (write :a)
                   b (m-result 2)]
                  (+ a b))
         [3 #{:a}]))
  (is (= (domonad (writer-m ())
                  [_ (domonad
                      [_ (write "foo")]
                      nil)
                   _ (write "bar")]
                  1)
         [1 '("foo" "bar")])))

(deftest reader-monad
  (let [monad-value (domonad reader-m
                             [x (asks :number)]
                             (* x 2))]
    (is (= (monad-value {:number 3})
           6)))

  (let [monad-value (domonad reader-m
                             [env (ask)]
                             env)]
    (is (= (monad-value "env")
           "env")))

  (let [monad-value (domonad reader-m
                             [numbers (ask)
                              sum  (m-result (reduce + numbers))
                              mean (m-result (/ sum (count numbers)))]
                             mean)]
    (is (= (monad-value (range 1 10))
           5)))

  (let [monad-value (domonad reader-m
                             [a (ask)
                              b (local inc (ask))]
                             (* a b))]
    (is (= (monad-value 10)
           110)))


  (let [mult-a-b (fn []
                   (domonad reader-m
                            [a (asks :a)
                             b (asks :b)]
                            (* a b)))
        monad-value (domonad reader-m
                             [a  (asks :a)
                              b  (asks :b)
                              a* (local #(update-in % [:a] inc) (asks :a))
                              c  (local #(assoc % :b 5)  (mult-a-b))]
                             [a b a* c])]
    (= (monad-value {:a 10})
       [10 nil 11 50])))

(deftest seq-maybe-monad
  (with-monad (maybe-t sequence-m)
    (letfn [(pairs [xs] ((m-lift 2 #(list %1 %2)) xs xs))]
      (are [a b] (= a b)
        ((m-lift 1 inc) (for [n (range 10)] (when (odd? n) n)))
          '(nil 2 nil 4 nil 6 nil 8 nil 10)
        (pairs (for [n (range 5)] (when (odd? n) n)))
          '(nil nil (1 1) nil (1 3) nil nil nil (3 1) nil (3 3) nil nil)))))

(deftest state-maybe-monad
  (with-monad (maybe-t state-m)
    (is (= (for [[a b c d] (list [1 2 3 4] [nil 2 3 4] [ 1 nil 3 4]
                                 [nil nil 3 4] [1 2 nil nil])]
             (let [f (domonad
                       [x (m-plus (m-result a) (m-result b))
                        y (m-plus (m-result c) (m-result d))]
                       (+ x y))]
               (f :state)))
           (list [4 :state] [5 :state] [4 :state] [nil :state] [nil :state])))))

(deftest state-seq-monad
  (with-monad (sequence-t state-m)
    (is (= (let [[a b c d] [1 2 10 20]
                 f (domonad
                     [x (m-plus (m-result a) (m-result b))
                      y (m-plus (m-result c) (m-result d))]
                     (+ x y))]
             (f :state)))
        (list [(list 11 21 12 22) :state]))))
