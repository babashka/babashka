(ns selmer.our-test
  "Some additional tests we added ourselves"
  (:require [clojure.test :as t :refer [deftest is testing]]
            [selmer.parser :as selmer]
            [selmer.util :as util]
            [selmer.validator :as validator]))

(deftest escaping-test
  (testing "escaping by default"
    (is (= "&amp;foo" (selmer/render "{% firstof foo bar %}" {:foo "&foo" :bar 2}))))
  (testing "can be disabled"
    (util/turn-off-escaping!)
    (is (= "&foo" (selmer/render "{% firstof foo bar %}" {:foo "&foo" :bar 2}))))
  (testing "can be re-enabled"
    (util/turn-on-escaping!)
    (prn util/*escape-variables*)
    (is (= "&amp;foo" (selmer/render "{% firstof foo bar %}" {:foo "&foo" :bar 2}))))
  (testing "macros"
    (is (= "&foo" (util/without-escaping (selmer/render "{% firstof foo bar %}" {:foo "&foo" :bar 2}))))
    (is (= "&amp;foo" (util/with-escaping (selmer/render "{% firstof foo bar %}" {:foo "&foo" :bar 2})))))
  (testing "missing value"
    (util/set-missing-value-formatter! (constantly "<missing>"))
    (is (= "<missing>" (selmer/render "{{ foo }}" {}))))
  #_(testing "validator off"
    (is (thrown? Exception (selmer/render "{% if foo %} yeah " {:foo true})))
    (validator/validate-off!)
    (is (selmer/render "{% if foo %} yeah " {:foo true}))))

