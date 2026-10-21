(ns proj.a-test
  (:require [clojure.test :refer [deftest is]]))

(deftest mine (is (= 1 1)))

(deftest ^:smoke smoke (is true))

(deftest ^:smoke ^:integration smoke-integration (is true))
