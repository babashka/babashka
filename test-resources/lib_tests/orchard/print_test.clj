(ns orchard.print-test
  (:require
   [clojure.test :as t :refer [is are deftest testing]]
   [orchard.print :as sut]
   [orchard.test.util :refer [is+]])
  (:import
   (mx.cider.orchard TruncatingStringWriter
                     TruncatingStringWriter$TotalLimitExceeded)))

;; for `match?`
(require 'matcher-combinators.test)

(def long-nested-coll (vec (map #(range (* % 10) (+ (* % 10) 80)) (range 200))))
(def graph-with-loop (let [a (java.util.HashMap.)
                           b (java.util.HashMap.)]
                       (.put a :b b)
                       (.put b :a a)
                       a))
(def infinite-map (let [m (java.util.HashMap.)]
                    (.put m (symbol "long key to ensure total length works accurately") m)
                    m))

(defn nasty [lvl]
  (if (= lvl 0)
    1234
    (into {} (map (fn [k]
                    [k (nasty (dec lvl))])
                  (map (comp keyword str char) (range (int \a) (int \n)))))))

(defprotocol IMyTestType
  (^String get-name [this]))

(deftype MyTestType [name]
  IMyTestType
  (get-name [_this] name))

(defmethod sut/print MyTestType [obj ^java.io.Writer w]
  (.write w (str "#<MyTestType " (get-name obj) ">")))

(defrecord TestRecord [a b c d])
(defrecord EmptyRecord [])

(defn sample-writer [atom-limit total-limit & strings]
  (let [writer (TruncatingStringWriter. atom-limit total-limit)]
    (try (run! #(.write writer ^String %) strings)
         (catch TruncatingStringWriter$TotalLimitExceeded _))
    (str writer)))

(deftest truncating-string-writer
  (are [result strings] (match? result (apply sample-writer 5 20 strings))
    "1" ["1"]
    "hello" ["hello"]
    "longs..." ["longstring"]
    "longs...longs...tail" ["longstring" "longstring" "tail"]
    "longs...longs...unfi..." ["longstring" "longstring" "unfit"]
    "hellohellohellohello" ["hello" "hello" "hello" "hello"]
    "hellohellohellohello..." ["hello" "hello" "hello" "hello" "hello"]
    "hellohellohihihihell..." ["hello" "hello" "hi" "hi" "hi" "hello"]
    "" ["" "" "" "" ""]))

(deftest print-no-limits
  (are [result form] (match? result (sut/print-str form))
    "1" 1
    "\"2\"" "2"
    "\"special \\\" \\\\ symbols\"" "special \" \\ symbols"
    ":foo" :foo
    ":abc/def" :abc/def
    "sym" 'sym
    "\\space" \space
    "(:a :b :c)" '(:a :b :c)
    "[1 2 3]" [1 2 3]
    "{:a 1, :b 2}" {:a 1 :b 2}
    "[:a 1]" (first {:a 1 :b 2})
    "([:a 1] [:b 2])" (seq {:a 1 :b 2})
    "[[:a 1] [:b 2]]" (vec {:a 1 :b 2})
    "{}" {}
    "{}" (java.util.HashMap.)
    "#{:a}" #{:a}
    "(1 2 3)" (lazy-seq '(1 2 3))
    "(1 1 1 1 1)" (java.util.ArrayList. ^java.util.Collection (repeat 5 1))
    "{:a 1, :b 2}" (let [^java.util.Map x {:a 1 :b 2}]
                     (java.util.HashMap. x))
    "#TestRecord{:a 1, :b 2, :c 3, :d 4}" (->TestRecord 1 2 3 4)
    "#EmptyRecord{}" (->EmptyRecord)
    "long[] {1, 2, 3, 4}" (long-array [1 2 3 4])
    "long[] {}" (long-array [])
    "java.lang.Long[] {0, 1, 2, 3, 4}" (into-array Long (range 5))
    "java.lang.Long[] {}" (into-array Long [])
    "#<MyTestType test1>" (MyTestType. "test1")
    "#atom[1]" (atom 1)
    "#delay[<pending>]" (delay 1)
    "#delay[1]" (doto (delay 1) deref)
    #"#delay\[<failed> #error\[java.lang.ArithmeticException \"Divide by zero\"" (let [d (delay (/ 1 0))] (try @d (catch Exception _)) d)
    "#promise[<pending>]" (promise)
    "#promise[1]" (doto (promise) (deliver 1))
    "#future[<pending>]" (future (Thread/sleep 10000))
    "#future[1]" (doto (future 1) deref)
    "#agent[1]" (agent 1)
    #"#error\[clojure.lang.ExceptionInfo \"Boom\" \".+\"\]" (ex-info "Boom" {})
    #"#error\[clojure.lang.ExceptionInfo \"Boom\" \{:a 1\} \".+\"\]" (ex-info "Boom" {:a 1})
    #"#error\[java.lang.RuntimeException \"Runtime!\" \".+\"\]" (RuntimeException. "Runtime!")
    #"#error\[java.lang.RuntimeException \"Outer: Inner\" \".+\"\]" (RuntimeException. "Outer"
                                                                                                         (RuntimeException. "Inner"))
    #"multifn\[print .+\]" sut/print
    "#function[clojure.core/str]" str))

(deftest print-writer-limits
  (testing "global writer limits will stop the printing when reached"
    (are [result form] (= result (binding [sut/*max-atom-length* 10
                                           sut/*max-total-length* 30]
                                   (sut/print-str form)))
      "\"aaaaaaaaaa...\"" (apply str (repeat 300 "a"))
      "[\"aaaaaaaaaa...\" \"aaaaaaaaaa....." [(apply str (repeat 300 "a")) (apply str (repeat 300 "a"))]
      "(1 1 1 1 1 1 1 1 1 1 1 1 1 1 1..." (repeat 1)
      "[(1 1 1 1 1 1 1 1 1 1 1 1 1 1 ..." [(repeat 1)]
      "{:a {(0 1 2 3 4 5 6 7 8 9) 1, ..." {:a {(range 10) 1, 2 3, 4 5, 6 7, 8 9, 10 11}}
      "(1 1 1 1 1 1 1 1 1 1 1 1 1 1 1..." (java.util.ArrayList. ^java.util.Collection (repeat 100 1))
      "java.lang....[] {0, 1, 2, 3, 4..." (into-array Long (range 10))
      "{:m {:m {:m {:m {:m 1234, :e 1..." (nasty 5)
      "{:b {:a {:b {:a {:b {:a {:b {:..." graph-with-loop))

  (testing "writer won't go much over total-length"
    (is (= 2003 (count (binding [sut/*max-total-length* 2000]
                         (sut/print-str infinite-map)))))))

(deftest print-clojure-limits
  (are [result form] (= result (binding [*print-length* 5
                                         *print-level* 3]
                                 (sut/print-str form)))
    "(1 1 1 1 1 ...)" (repeat 1)
    "[(1 1 1 1 1 ...)]" [(repeat 1)]
    "{:a {(0 1 2 3 4 ...) 1, 2 3, 4 5, 6 7, 8 9, ...}}" {:a {(range 10) 1, 2 3, 4 5, 6 7, 8 9, 10 11}}
    "(1 1 1 1 1 ...)" (java.util.ArrayList. ^java.util.Collection (repeat 100 1))
    "(0 1 2 3 4 ...)" (eduction (range 10))
    "java.lang.Long[] {0, 1, 2, 3, 4, ...}" (into-array Long (range 10))
    "{:b {:a {:b {...}}}}" graph-with-loop)

  (are [result form] (= result (binding [*print-length* 3
                                         *print-level* 2]
                                 (sut/print-str form)))
    "{:a {(...) 1, 2 3, 4 5, ...}}" {:a {(range 10) 1, 2 3, 4 5, 6 7, 8 9, 10 11}}
    "[(0 1 2 ...) (10 11 12 ...) (20 21 22 ...) ...]" long-nested-coll
    "{:m {:m {...}, :e {...}, :l {...}, ...}, :e {:m {...}, :e {...}, :l {...}, ...}, :l {:m {...}, :e {...}, :l {...}, ...}, ...}" (nasty 5))

  (are [result lvl] (= result (binding [*print-level* lvl]
                                (sut/print-str (atom {:a (range 10)}))))
    "#atom[...]" 0
    "#atom[{...}]" 1
    "#atom[{:a (...)}]" 2
    "#atom[{:a (0 1 2 3 4 5 6 7 8 9)}]" 3))

(defmethod orchard.print/print ::custom-rec [_ w] (sut/print 'hello w))

(deftest print-custom-print-method
  (is (= "hello"
         (sut/print-str (with-meta (->TestRecord 1 2 3 4) {:type ::custom-rec})))))

(deftest qualified-keywords-compaction
  (are [kw repr] (= repr (sut/print-str kw))
    :foo     ":foo"
    :foo/bar ":foo/bar"
    ::foo    ":orchard.print-test/foo"
    ::t/foo  ":clojure.test/foo")
  (is (= ":foo" (sut/print-str :foo)))
  (is (= ":foo/bar" (sut/print-str :foo/bar)))
  (is (= ":orchard.print-test/foo" (sut/print-str ::foo)))
  (is (= ":clojure.test/foo" (sut/print-str :clojure.test/foo)))

  (testing "binding *pov-ns* enables keyword compaction"
    (binding [sut/*pov-ns* (find-ns 'orchard.print-test)]
      (are [kw repr] (= repr (sut/print-str kw))
        :foo             ":foo"
        :foo/bar         ":foo/bar"
        ::foo            "::foo"
        ::t/foo          "::t/foo"
        :clojure.set/foo ":clojure.set/foo")))

  (testing "from other pov NS the printing will be different"
    (binding [sut/*pov-ns* (create-ns 'throwaway)]
      (are [kw repr] (= repr (sut/print-str kw))
        ::foo    ":orchard.print-test/foo"
        ::t/foo          ":clojure.test/foo"))))

(deftest short-record-names-test
  (testing "*short-record-names* controls how records are printed"
    (is (= "#TestRecord{:a 1, :b 2, :c 3, :d 4}" (sut/print-str (->TestRecord 1 2 3 4))))
    (binding [sut/*short-record-names* false]
      (is (= "#orchard.print_test.TestRecord{:a 1, :b 2, :c 3, :d 4}"
             (sut/print-str (->TestRecord 1 2 3 4)))))))
