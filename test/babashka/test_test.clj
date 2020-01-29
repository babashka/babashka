(ns babashka.test-test
  (:require
   [babashka.test-utils :as tu]
   [clojure.string :as str]
   [clojure.test :as t :refer [deftest is]]))

(defn bb [& args]
  (apply tu/bb nil (map str args)))

(deftest deftest-test
  (is (str/includes?
       (bb "(require '[clojure.test :as t]) (t/deftest foo (t/is (= 4 5))) (foo)")
       "expected: (= 4 5)\n  actual: false\n")))

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
