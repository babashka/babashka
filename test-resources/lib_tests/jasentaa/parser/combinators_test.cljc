(ns jasentaa.parser.combinators-test
  (:require
   #?(:clj  [clojure.test :refer :all]
      :cljs [cljs.test :refer-macros [deftest is testing]])
   [jasentaa.monad :as m]
   [jasentaa.test-helpers :as th]
   [jasentaa.parser.basic :as pb]
   [jasentaa.parser.combinators :as pc]))

(deftest check-and-then
  (let [parser (pc/and-then (pb/match "a") (pb/match "b"))]
    (is (= [[[\a \b] "el"]] (th/test-harness parser "abel")))
    (is (= (m/failure)   (th/test-harness parser "apple")))
    (is (= (m/failure)   (th/test-harness parser "")))))

(deftest check-or-else
  (let [parser (pc/or-else (pb/match "a") (pb/match "b"))]
    (is (= [[\a "pple"]]  (th/test-harness parser "apple")))
    (is (= [[\b "anana"]] (th/test-harness parser "banana")))
    (is (= (m/failure)    (th/test-harness parser "orange")))))

(deftest check-many
  (let [parser (pc/many (pb/match "a"))]
    (is (= [[\a] ""]          (first (th/test-harness parser "a"))))
    (is (= [[\a \a \a] "bbb"] (first (th/test-harness parser "aaabbb"))))
    (is (= [[] nil]           (first (th/test-harness parser ""))))
    (is (= [[\a] "pple"]      (first (th/test-harness parser "apple"))))
    (is (= [[] "orange"]      (first (th/test-harness parser "orange"))))))
