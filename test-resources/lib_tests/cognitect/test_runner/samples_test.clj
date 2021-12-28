(ns cognitect.test-runner.samples-test
  (:require [clojure.test :as t :refer [deftest is testing]]))

(deftest math-works
  (testing "basic addition and subtraction"
    (is (= 42 (+ 40 2)))
    (is (= 42 (- 44 2)))))

(deftest ^:integration test-i
  (is (= 1 1)))




