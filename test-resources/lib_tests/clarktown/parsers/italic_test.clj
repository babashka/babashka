(ns clarktown.parsers.italic-test
  (:require
    [clojure.test :refer [deftest testing is]]
    [clarktown.parsers.italic :as italic]))


(deftest italic-test
  (testing "Creating italic text with one surrounding asterisk character"
    (is (= "<em>This is italic.</em>"
           (italic/render "*This is italic.*" nil))))

  (testing "Creating italic text with one surrounding underscore character"
    (is (= "<em>This is italic.</em>"
           (italic/render "_This is italic._" nil))))

  (testing "Creating italic text with both underscores and asterisks mixed"
    (is (= "Hi, my name is <em>John</em>, what is <em>your name?</em>"
           (italic/render "Hi, my name is *John*, what is _your name?_" nil)))))