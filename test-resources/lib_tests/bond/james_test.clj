(ns bond.james-test
  {:clj-kondo/config {:linters {:private-call {:level :off}
                                :invalid-arity {:level :off}}}}
  (:require [clojure.test :refer (deftest is testing)]
            [bond.james :as bond :include-macros true]
            [bond.target-data :as target]))

(deftest spy-logs-args-and-results
  (bond/with-spy [target/foo]
    (is (= 2 (target/foo 1)))
    (is (= 4 (target/foo 2)))
    (is (= [{:args [1] :return 2}
            {:args [2] :return 4}]
           (bond/calls target/foo)))
    (let [exception (is (thrown? clojure.lang.ArityException (target/foo 3 4)))]
      (is (= {:args [3 4] :throw exception}
             (-> target/foo bond/calls last))))))

(deftest calls-fails-on-unspied-fns
  (is (thrown? IllegalArgumentException
               (bond/calls target/foo))))

(deftest spy-can-spy-private-fns
  (bond/with-spy [target/private-foo]
    (is (= 4 (#'target/private-foo 2)))
    (is (= 6 (#'target/private-foo 3)))
    (is (= [{:args [2] :return 4}
            {:args [3] :return 6}]
           (bond/calls #'target/private-foo)))))

(deftest stub-works
  (is (= ""
         (with-out-str
           (bond/with-stub [target/bar]
             (target/bar 3))))))

(deftest stub-works-with-private-fn
  (testing "without replacement"
    (bond/with-stub [target/private-foo]
      (is (nil? (#'target/private-foo 3)))
      (is (= [3] (-> #'target/private-foo bond/calls first :args)))))
  (testing "with replacement"
    (bond/with-stub [[target/private-foo (fn [x] (* x x))]]
      (is (= 9 (#'target/private-foo 3)))
      (is (= [3] (-> #'target/private-foo bond/calls first :args))))))

(deftest stub-with-replacement-works
  (bond/with-stub [target/foo
                   [target/bar #(str "arg is " %)]]
    (testing "stubbing works"
      (is (nil? (target/foo 4)))
      (is (= "arg is 3" (target/bar 3))))
    (testing "spying works"
      (is (= [4] (-> target/foo bond/calls first :args)))
      (is (= [3] (-> target/bar bond/calls first :args))))))


(deftest iterator-style-stubbing-works
  (bond/with-stub [target/foo
                   [target/bar [#(str "first arg is " %)
                                #(str "second arg is " %)
                                #(str "third arg is " %)]]]
    (testing "stubbing works"
      (is (nil? (target/foo 4)))
      (is (= "first arg is 3" (target/bar 3)))
      (is (= "second arg is 4" (target/bar 4)))
      (is (= "third arg is 5" (target/bar 5))))
    (testing "spying works"
      (is (= [4] (-> target/foo bond/calls first :args)))
      (is (= [3] (-> target/bar bond/calls first :args)))
      (is (= [4] (-> target/bar bond/calls second :args)))
      (is (= [5] (-> target/bar bond/calls last :args))))))

(deftest stub!-complains-loudly-if-there-is-no-arglists
  (is (thrown? IllegalArgumentException
               (bond/with-stub! [[target/without-arglists (constantly 42)]]
                 (throw (Exception. "shouldn't get here"))))))

(deftest stub!-throws-arity-exception
  (bond/with-stub! [[target/foo (constantly 9)]]
    (is (= 9 (target/foo 12)))
    (is (= [{:args [12] :return 9}] (bond/calls target/foo))))
  (bond/with-stub! [target/bar
                    target/quuk
                    [target/quux (fn [_ _ & x] x)]]
    (is (thrown? clojure.lang.ArityException
                 (target/bar 1 2)))
    (is (thrown? clojure.lang.ArityException
                 (target/quuk 1)))
    (is (= [6 5] (target/quux 8 7 6 5)))))

(deftest spying-entire-namespaces-works
  (bond/with-spy-ns [bond.target-data]
    (target/foo 1)
    (target/foo 2)
    (is (= [{:args [1] :return 2}
            {:args [2] :return 4}]
           (bond/calls target/foo)))
    (is (= 0 (-> target/bar bond/calls count)))))

(deftest stubbing-entire-namespaces-works
  (testing "without replacements"
    (bond/with-stub-ns [bond.target-data]
      (is (nil? (target/foo 10)))
      (is (= [10] (-> target/foo bond/calls first :args)))))
  (testing "with replacements"
    (bond/with-stub-ns [[bond.target-data (constantly 3)]]
      (is (= 3 (target/foo 10)))
      (is (= [10] (-> target/foo bond/calls first :args))))))
