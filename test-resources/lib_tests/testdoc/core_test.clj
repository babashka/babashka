(ns testdoc.core-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :as t]
   [testdoc.core :as sut]))

(defn- repl-styled-success-test-func
  "foo bar

  => (+ 1 2 3)
  6
  => (+ 1 2
  =>    3 4)
  10
  => *1
  10
  => (inc *1)
  11"
  [])

(defn- code-first-styled-success-test-func
  "foo bar

  (+ 1 2 3)
  ;; => 6

  (+ 1 2
     3 4)
  ;; => 10
  *1
  ;; => 10
  (inc *1)
  ;; => 11"
  [])

(defn- repl-styled-partial-success-test-func
  "foo bar

  => (+ 1 2 3)
  6
  => (+ 1 2 3 4)
  999"
  [])

(defn- code-first-styled-partial-success-test-func
  "foo bar

  (+ 1 2 3)
  ;; => 6
  (+ 1 2 3 4)
  ;; => 999"
  [])

(t/deftest testdoc-test
  (t/testing "repl style"
    (t/is (= [{:type :pass :expected 6 :actual 6}
              {:type :pass :expected 10 :actual 10}
              {:type :pass :expected 10 :actual 10}
              {:type :pass :expected 11 :actual 11}]
             (->> (sut/testdoc nil #'repl-styled-success-test-func)
                  (map #(select-keys % [:type :expected :actual]))
                  (sort-by :expected))))

    (t/is (= [{:type :pass :expected 6 :actual 6}
              {:type :fail :expected 999 :actual 10}]
             (->> (sut/testdoc nil #'repl-styled-partial-success-test-func)
                  (map #(select-keys % [:type :expected :actual]))
                  (sort-by :expected)))))

  (t/testing "code-first style"
    (t/is (= [{:type :pass :expected 6 :actual 6}
              {:type :pass :expected 10 :actual 10}
              {:type :pass :expected 10 :actual 10}
              {:type :pass :expected 11 :actual 11}]
             (->> (sut/testdoc nil #'code-first-styled-success-test-func)
                  (map #(select-keys % [:type :expected :actual]))
                  (sort-by :expected))))

    (t/is (= [{:type :pass :expected 6 :actual 6}
              {:type :fail :expected 999 :actual 10}]
             (->> (sut/testdoc nil #'code-first-styled-partial-success-test-func)
                  (map #(select-keys % [:type :expected :actual]))
                  (sort-by :expected))))))

(t/deftest testdoc-unsupported-test
  (let [[result :as results] (sut/testdoc nil 123)]
    (t/is (= 1 (count results)))
    (t/is (= :fail (:type result)))
    (t/is (re-seq #"^Unsupported document:" (:message result)))))

(defn plus
  "Add a and b

  => (plus 1 2)
  3
  => (plus 2
  =>       3)
  5"
  [a b]
  (+ a b))

(t/deftest plus-test
  (t/is (testdoc #'plus)))

(t/deftest plus-string-test
  (t/is (testdoc "=> (require '[testdoc.core-test :as ct])
                  nil
                  => (ct/plus 1 2)
                  3
                  => (ct/plus 2
                  =>          3)
                  5")))

(t/deftest nil-value-test
  (t/is (= [{:type :fail :message "(= 1 nil)" :expected nil :actual 1}]
           (sut/testdoc nil "=> 1
                             nil"))))

(t/deftest unresolved-symbol-test
  (let [[err :as res] (sut/testdoc nil "
                                        => (unresolved-fn 10)
                                        11")]
    (t/is (= 1 (count res)))
    (t/is (= :fail (:type err)))
    (t/is (every? #(some? (get err %)) [:type :message :expected :actual]))
    (t/is (= "(= (unresolved-fn 10) 11), [line: 2]" (:message err)))))

(t/deftest debug-test
  (with-out-str
    (t/is (testdoc #'sut/debug))))

(t/deftest README-test
  (t/is (testdoc (slurp (io/file "README.md")))))
