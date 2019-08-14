(ns babashka.main-test
  (:require
   [clojure.test :as test :refer [deftest is testing]]
   [babashka.test-utils :as test-utils]
   [babashka.main :as main]
   [clojure.edn :as edn]
   [clojure.string :as str]))

(defn bb [input & args]
  (edn/read-string (apply test-utils/bb (str input) (map str args))))

(deftest main-test
  (testing "-io behaves as identity"
    (= "foo\nbar\n" (test-utils/bb "foo\nbar\n" "-io" "*in*")))
  (testing "if and when"
    (is (= 1 (bb 0 '(if (zero? *in*) 1 2))))
    (is (= 2 (bb 1 '(if (zero? *in*) 1 2))))
    (is (= 1 (bb 0 '(when (zero? *in*) 1))))
    (is (nil? (bb 1 '(when (zero? *in*) 1)))))
  (testing "and and or"
    (is (= false (bb 0 '(and false true *in*))))
    (is (= 0 (bb 0 '(and true true *in*))))
    (is (= 1 (bb 1 '(or false false *in*))))
    (is (= false (bb false '(or false false *in*))))
    (is (= 3 (bb false '(or false false *in* 3)))))
  (testing "fn"
    (is (= 2 (bb 1 "(#(+ 1 %) *in*)")))
    (is (= [1 2 3] (bb 1 "(map #(+ 1 %) [0 1 2])")))
    (is (bb 1 "(#(when (odd? *in*) *in*) 1)")))
  (testing "map"
    (is (= [1 2 3] (bb 1 '(map inc [0 1 2])))))
  (testing "keep"
    (is (= [false true false] (bb 1 '(keep odd? [0 1 2])))))
  (testing "->"
    (is (= 4 (bb 1 '(-> *in* inc inc (inc))))))
  (testing "->>"
    (is (= 10 (edn/read-string (test-utils/bb "foo\n\baar\baaaaz" "-i" "(->> *in* (map count) (apply max))")))))
  (testing "literals"
    (is (= {:a 4
            :b {:a 2}
            :c [1 1]
            :d #{1 2}}
           (bb 1 '{:a (+ 1 2 *in*)
                   :b {:a (inc *in*)}
                   :c [*in* *in*]
                   :d #{*in* (inc *in*)}}))))
  (testing "shuffle the contents of a file"
    (let [in "foo\n Clojure is nice. \nbar\n If you're nice to clojure. "
          in-lines (set (str/split in #"\n"))
          out (test-utils/bb in
                             "-io"
                             (str '(shuffle *in*)))
          out-lines (set (str/split out #"\n"))]
      (is (= in-lines out-lines))))
  (testing "find occurrences in file by line number"
    (is (= '(1 3)
           (->
            (bb "foo\n Clojure is nice. \nbar\n If you're nice to clojure. "
                "-i"
                "(map-indexed #(-> [%1 %2]) *in*)")
            (bb "(keep #(when (re-find #\"(?i)clojure\" (second %)) (first %)) *in*)"))))))

(deftest input-test
  (testing "bb doesn't wait for input if *in* isn't used"
    (is (= "2\n" (with-out-str (main/main "(inc 1)"))))))

(deftest System-test
  (let [res (bb nil "-f" "test/babashka/scripts/System.bb")]
    (is (= "bar" (second res)))
    (doseq [s res]
      (is (not-empty s)))))
