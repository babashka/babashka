(ns jasentaa.parser.basic-test
  (:require
   #?(:clj  [clojure.test :refer :all]
      :cljs [cljs.test :refer-macros [deftest is testing]])
   [jasentaa.test-helpers :as th]
   [jasentaa.monad :as m]
   [jasentaa.parser.basic :as pb]))

(deftest check-any
  (is (= [[\a "pple"]] (th/test-harness pb/any "apple")))
  (is (= [[\a ""]]     (th/test-harness pb/any "a")))
  (is (= (m/failure)   (th/test-harness pb/any [])))
  (is (= (m/failure)   (th/test-harness pb/any nil)))
  (is (= (m/failure)   (th/test-harness pb/any ""))))

(deftest check-match
  (is (= [[\a "pple"]] (th/test-harness (pb/match "a") "apple")))
  (is (= [[\a ""]]     (th/test-harness (pb/match "a") "a")))
  (is (= (m/failure)   (th/test-harness (pb/match "a") "banana"))))

(deftest check-none-of
  (is (= [[\b "anana"]] (th/test-harness (pb/none-of "a") "banana")))
  (is (= [[\b ""]]      (th/test-harness (pb/none-of "a") "b")))
  (is (= (m/failure)    (th/test-harness (pb/none-of "b") "banana"))))

(deftest check-from-re
  (is (= [[\a "pple"]]  (th/test-harness (pb/from-re #"[a-z]") "apple")))
  (is (= [[\b "anana"]] (th/test-harness (pb/from-re #"[a-z]") "banana")))
  (is (= [[\p "ear"]]   (th/test-harness (pb/from-re #"[a-z]") "pear")))
  (is (= (m/failure)    (th/test-harness (pb/from-re #"[a-z]") "Tomtato"))))
