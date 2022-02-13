(ns com.wsscode.misc.macros-test
  (:require
    [clojure.test :refer [deftest is are run-tests testing]]
    [com.wsscode.misc.macros :as macros]))

(deftest full-symbol-test
  (is (= (macros/full-symbol
           'known/foo
           "bar")
         'known/foo))
  (is (= (macros/full-symbol
           'foo
           "bar")
         'bar/foo)))
