(ns clarktown.parsers.strikethrough-test
  (:require
    [clojure.test :refer [deftest testing is]]
    [clarktown.parsers.strikethrough :as strikethrough]))


(deftest strikethrough-test
  (testing "Creating strikethrough text"
    (is (= (strikethrough/render "~~This is strikethrough text.~~" nil)
           "<del>This is strikethrough text.</del>")))

  (testing "Creating strikethrough text mixed with regular text"
    (is (= (strikethrough/render "Some other text, ~~This is strikethrough text.~~ And more text." nil)
           "Some other text, <del>This is strikethrough text.</del> And more text."))))