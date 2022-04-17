(ns clarktown.parsers.quote-block-test
  (:require
    [clojure.test :refer [deftest testing is]]
    [clarktown.parsers.quote-block :as quote-block]))


(deftest quote-block-block-test
  (testing "Creating a quote block line"
    (is (= (quote-block/render "> First line\n> second line" nil)
           "<blockquote>First line\nsecond line</blockquote>")))

  (testing "Checking a quote block"
    (is (true? (quote-block/is? "> Test")))
    (is (true? (quote-block/is? "    > Test")))
    (is (true? (quote-block/is? ">")))))