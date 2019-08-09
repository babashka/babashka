(ns babashka.main-test
  (:require
   [clojure.test :as test :refer [deftest is testing]]
   [babashka.test-utils :as test-utils]
   [clojure.edn :as edn]))

(defn bb [input & args]
  (edn/read-string (apply test-utils/bb (str input) (map str args))))

(deftest main-test
  (testing "if and when"
    (is (= 1 (bb 0 '(if (zero? *in*) 1 2))))
    (is (= 2 (bb 1 '(if (zero? *in*) 1 2))))
    (is (= 1 (bb 0 '(when (zero? *in*) 1))))
    (is (nil? (bb 1 '(when (zero? *in*) 1)))))
  (testing "fn"
    (is (= 2 (bb 1 "(#f(+ 1 %) *in*)")))
    (is (= [1 2 3] (bb nil "(map #f(+ 1 %) [0 1 2])")))
    (is (bb 1 "(#f (when (odd? *in*) *in*) 1)")))
  (testing "map"
    (is (= [1 2 3] (bb nil '(map inc [0 1 2])))))
  (testing "keep"
    (is (= [false true false] (bb nil '(keep odd? [0 1 2])))))
  (testing "..."
    (is (= '(1 3)
           (->
            (bb "foo\n Clojure is nice. \nbar\n If you're nice to clojure. "
                "--raw"
                "(map-indexed #f[%1 %2] *in*)")
            (bb "(keep #f(when (re-find #r\"(?i)clojure\" (second %)) (first %)) *in*)"))))))
