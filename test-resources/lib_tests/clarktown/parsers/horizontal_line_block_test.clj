(ns clarktown.parsers.horizontal-line-block-test
  (:require
    [clojure.test :refer [deftest testing is]]
    [clarktown.parsers.horizontal-line-block :as horizontal-line-block]))


(deftest horizontal-line-block-test
  (testing "Creating a horizontal line"
    (is (= "<hr>"
           (horizontal-line-block/render "***" nil)))

    (is (= "<hr>"
           (horizontal-line-block/render "---" nil))))

  (testing "Is a horizontal line block"
    (is (true? (horizontal-line-block/is? "***")))
    (is (true? (horizontal-line-block/is? "    ***")))
    (is (false? (horizontal-line-block/is? "Test *** 123")))
    (is (true? (horizontal-line-block/is? "---")))
    (is (true? (horizontal-line-block/is? "    ---")))
    (is (false? (horizontal-line-block/is? "Test --- 123")))))