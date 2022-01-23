(ns doric.test.readme
  (:use [clojure.test]
        [doric.test.doctest]))

(deftest readme
  (run-doctests markdown-tests "README.md"))
