(ns camel-snake-kebab.internals.string-separator-test
  (:require [camel-snake-kebab.internals.string-separator :refer [split generic-separator]]
            #?(:clj [clojure.test :refer :all]
               :cljs [cljs.test :refer-macros [deftest testing is are]])))

(deftest split-test
  (testing "regex, string and character separators"
    (are [sep]
      (and (= ["foo" "bar"] (split sep "foo.bar"))
           (= [""]          (split sep "")))
      #"\." "." \.))

  (testing "input consisting of separator(s)"
    (is (empty? (split "x" "x")))
    (is (empty? (split "x" "xx"))))

  (testing "generic separator"
    (are [x y]
      (= x (split generic-separator y))

      [""]  ""
      [""]  "   "
      ["x"] " x "

      ["foo" "bar"] "foo bar"
      ["foo" "bar"] "foo\n\tbar"
      ["foo" "bar"] "foo-bar"
      ["foo" "Bar"] "fooBar"
      ["Foo" "Bar"] "FooBar"
      ["foo" "bar"] "foo_bar"
      ["FOO" "BAR"] "FOO_BAR"

      ["räksmörgås"] "räksmörgås"

      ["IP" "Address"] "IPAddress"

      ["Adler" "32"]         "Adler32"
      ["Inet" "4" "Address"] "Inet4Address"
      ["Arc" "2" "D"]        "Arc2D"
      ["a" "123b"]           "a123b"
      ["A" "123" "B"]        "A123B")))
