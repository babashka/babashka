(ns com.wsscode.misc.math-test
  (:require
    [clojure.test :refer [deftest is are run-tests testing]]
    [com.wsscode.misc.math :as math]))

(deftest floor-test
  (is (= (math/floor 30.2) 30))
  (is (= (math/floor 30.9) 30)))

(deftest round-test
  (is (= (math/round 30.2) 30))
  (is (= (math/round 30.6) 31)))

(deftest ceil-test
  (is (= (math/ceil 30.2) 31))
  (is (= (math/ceil 30.9) 31)))

(deftest divmod-test
  (is (= (math/divmod 10 3)
         [3 1])))

(deftest parse-long-test
  (is (= (math/parse-long "21")
         21)))

(deftest parse-double-test
  (is (= (math/parse-double "21.3")
         21.3)))
