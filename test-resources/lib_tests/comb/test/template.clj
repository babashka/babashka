(ns comb.test.template
  (:use clojure.test)
  (:require [comb.template :as t] :reload))

(deftest eval-test
  (is (= (t/eval "foo") "foo"))
  (is (= (t/eval "<%= 10 %>") "10"))
  (is (= (t/eval "<%= x %>" {:x "foo"}) "foo"))
  (is (= (t/eval "<%=x%>" {:x "foo"}) "foo"))
  (is (= (t/eval "<% (doseq [x xs] %>foo<%= x %> <% ) %>" {:xs [1 2 3]})
         "foo1 foo2 foo3 ")))

(deftest fn-test
  (is (= ((t/fn [x] "foo<%= x %>") "bar")
         "foobar")))
