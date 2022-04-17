(ns clarktown.parsers.link-and-image-test
  (:require
    [clojure.test :refer [deftest testing is]]
    [clarktown.parsers.link-and-image :as link-and-image]))


(deftest link-test
  (testing "Creating a link"
    (is (= (link-and-image/render "[This is a link](https://example.com)" nil)
           "<a href=\"https://example.com\">This is a link</a>"))

    (is (= (link-and-image/render "[This-is-a-link](https://example.com)" nil)
           "<a href=\"https://example.com\">This-is-a-link</a>"))

    (is (= (link-and-image/render "[x] [label](link)" nil)
           "[x] <a href=\"link\">label</a>"))

    (is (= (link-and-image/render "[ ] [label](link)" nil)
           "[ ] <a href=\"link\">label</a>")))

  (testing "Creating an image"
    (is (= (link-and-image/render "![This is an image](https://example.com)" nil)
           "<img src=\"https://example.com\" alt=\"This is an image\">"))))