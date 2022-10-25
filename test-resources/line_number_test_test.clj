(ns line-number-test-test
  (:require [clojure.test :refer [is deftest run-tests]]))

(deftest test-is
  (is false))

(run-tests 'line-number-test-test)
