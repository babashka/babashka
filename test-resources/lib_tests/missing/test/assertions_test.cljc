(ns missing.test.assertions-test
  (:require
   [clojure.test :refer [deftest testing is] :as t]
   [missing.test.old-methods]
   [missing.test.assertions]))

(deftest a-test
  (testing "FIXME, I fail."
    1))

(deftest another-test
  (testing (is 1)))
