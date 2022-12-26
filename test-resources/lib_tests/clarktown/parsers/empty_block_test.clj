(ns clarktown.parsers.empty-block-test
  (:require
    [clojure.test :refer [deftest testing is]]
    [clarktown.parsers.empty-block :as empty-block]))


(deftest empty-block-test
  (testing "Rendering an empty block"
    (is (= (empty-block/render "" nil)
           "")))

  (testing "Checking an empty block"
    (is (true? (empty-block/is? "")))
    (is (true? (empty-block/is? "     ")))))
