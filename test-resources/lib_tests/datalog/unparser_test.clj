(ns datalog.unparser-test
  (:require [datalog.unparser :refer [unparse]]
            [datalog.parser :refer [parse]]
            [clojure.test      :refer [deftest testing is] :as test])
  (:use [datalog.unparser]))

(let [q '[:find (sum ?balance-before) ?balance-before
          :in $before $after $txn $txs
          :where
          [(= ?balance-before 42)]]]
  (deftest unparse-roundtrip-test
    (testing "Datahike query unparsing."
      (is (= q (unparse (parse q)))))))



(comment ;; TODO
  (let [q '[:find ?foo ?baz
            :in $before $after
            :where
            [(= ?balance-before 42)]
            (not [?foo :bar ?baz])]]
    (deftest unparse-roundtrip-test
      (testing "Datahike query unparsing."
        (is (= q (unparse (parse q))))))))
