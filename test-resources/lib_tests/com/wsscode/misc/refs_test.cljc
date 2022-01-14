(ns com.wsscode.misc.refs-test
  (:require
    [clojure.test :refer [deftest is are run-tests testing]]
    [com.wsscode.misc.refs :refer [atom?] :as refs]))

(deftest kw-identical?-test
  (is (not (refs/kw-identical? :foo :bar)))
  (is (not (refs/kw-identical? :foo "foo")))
  (is (refs/kw-identical? :foo :foo))
  (is (refs/kw-identical? :foo (keyword "foo"))))

(deftest atom?-test
  (is (true? (atom? (atom "x"))))
  (is (false? (atom? "x"))))

(deftest greset!-test
  (let [x (atom nil)]
    (refs/greset! x "val")
    (is (= @x "val")))

  (let [x (volatile! nil)]
    (refs/greset! x "val")
    (is (= @x "val"))))

(deftest gswap!-test
  (let [x (atom 10)]
    (refs/gswap! x inc)
    (is (= @x 11)))

  (let [x (volatile! 10)]
    (refs/gswap! x inc)
    (is (= @x 11)))

  (let [x (volatile! 10)]
    (refs/gswap! x + 1 2 3 4 5)
    (is (= @x 25))))
