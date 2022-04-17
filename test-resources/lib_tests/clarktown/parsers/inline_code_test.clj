(ns clarktown.parsers.inline-code-test
  (:require
    [clojure.test :refer [deftest testing is]]
    [clarktown.parsers.inline-code :as inline-code]))


(deftest inline-code-test
  (testing "Creating inline code text"
    (is (= "<code>This is inline code.</code>"
           (inline-code/render "`This is inline code.`" nil))))

  (testing "Creating inline-code text in the middle of regular text"
    (is (= "This is regular text, mixed with <code>some inline code.</code>, and it's great."
           (inline-code/render "This is regular text, mixed with `some inline code.`, and it's great." nil)))))