(ns assertion-file-test
  (:require [clojure.test :refer [deftest is]]))

(deftest failing (is (= 1 2)))
