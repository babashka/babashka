(ns babashka.test-test
  (:require
   [babashka.test-utils :as tu]
   [clojure.string :as str]
   [clojure.test :as t :refer [deftest is]]
   [clojure.java.io :as io]))

(defn bb [& args]
  (apply tu/bb nil (map str args)))

(deftest deftest-test
  (is (str/includes?
       (bb "(require '[clojure.test :as t]) (t/deftest foo (t/is (= 4 5))) (foo)")
       "expected: (= 4 5)\n  actual: (not (= 4 5))\n")))

(deftest run-tests-test
  (let [output (bb "(require '[clojure.test :as t]) (t/deftest foo (t/is (= 4 5))) (t/run-tests)")]
    (is (str/includes? output "Testing user"))
    (is (str/includes? output "{:test 1, :pass 0, :fail 1, :error 0, :type :summary}"))))

(deftest run-all-tests-test
  (let [output (bb "
(require '[clojure.test :as t])
(t/deftest foo (t/is (= 4 5)))
(ns foobar)
(require '[clojure.test :as t])
(t/run-all-tests)")]
    (is (str/includes? output "Testing user"))
    (is (str/includes? output "Testing foobar"))
    (is (str/includes? output "{:test 1, :pass 0, :fail 1, :error 0, :type :summary}"))))

(deftest fixtures-test
  (let [output (bb "
(require '[clojure.test :as t])
(defn once [f] (prn :once-before) (f) (prn :once-after))
(defn each [f] (prn :each-before) (f) (prn :each-after))
(t/use-fixtures :once once)
(t/use-fixtures :each each)
(t/deftest foo)
(t/deftest bar)
(t/run-tests)")]
    (is (str/includes? output (str/trim "
:once-before
:each-before
:each-after
:each-before
:each-after
:once-after")))))

(deftest with-test
  (let [output (bb "
(require '[clojure.test :as t])
(t/with-test
  (defn my-function [x y]
    (+ x y))
  (t/is (= 4 (my-function 2 2)))
  (t/is (= 7 (my-function 3 4))))
(t/run-tests)")]
    (is (str/includes? output "Ran 1 tests containing 2 assertions."))))

(deftest testing-test
  (is (str/includes? (bb "(require '[clojure.test :as t]) (t/testing \"foo\" (t/is (= 4 5)))")
                     "foo")))

(deftest are-test
  (is (str/includes? (bb "(require '[clojure.test :as t]) (t/are [x y] (= x y) 2 (+ 1 2))")
                     "expected: (= 2 (+ 1 2))")))

(deftest assert-expr-test
  (is (str/includes? (bb (.getPath (io/file "test-resources" "babashka" "assert_expr.clj")))
                     "3.14 should be roughly 3.141592653589793")))
