(ns bond.assertions-test
  (:require [clojure.test :refer (deftest is testing)]
            [bond.assertions :as assertions]
            [bond.james :as bond :include-macros true]
            [bond.target-data :as target]))

(deftest called?-works
  (testing "a spy was called directly"
    (bond/with-spy [target/foo]
      (target/foo 1)
      (is (assertions/called? target/foo))))

  (testing "a spy was called indirectly"
    (bond/with-spy [target/foo]
      (target/foo-caller 1)
      (is (assertions/called? target/foo))))

  (testing "a spy was not called"
    (bond/with-spy [target/foo]
      (is (not (assertions/called? target/foo)))))

  (testing "called? fails when its argument is not spied"
    (is (thrown? IllegalArgumentException
                 (assertions/called? target/foo)))))

(deftest called-times?-works
  (testing "the number of times a spy was called"
    (bond/with-spy [target/foo]
      (target/foo-caller 1)
      (is (assertions/called-times? target/foo 1))
      (target/foo 2)
      (is (assertions/called-times? target/foo 2))))

  (testing "the number of times a spy was not called"
    (bond/with-spy [target/foo]
      (target/foo-caller 1)
      (is (not (assertions/called-times? target/foo 2)))
      (target/foo-caller 2)
      (is (not (assertions/called-times? target/foo 1)))))

  (testing "called-times? fails when its argument is not spied"
    (is (thrown? IllegalArgumentException
                 (assertions/called-times? target/foo 0)))))

(deftest called-with-args?-works
  (testing "an assertion for calling a spy with args"
    (bond/with-spy [target/foo
                    target/bar]
      (target/foo-caller 1)
      (is (assertions/called-with-args? target/foo [[1]]))
      (is (not (assertions/called-with-args? target/foo [[2]])))
      (is (not (assertions/called-with-args? target/bar [[1]])))
      (is (not (assertions/called-with-args? target/foo [[1 2]])))))

  (testing "an assertion for calling a spy multiple times with args"
    (bond/with-spy [target/foo]
      (target/foo-caller 1)
      (target/foo-caller 2)
      (is (assertions/called-with-args? target/foo [[1] [2]]))))

  (testing "called-with-args? fails when its argument is not spied"
    (is (thrown? IllegalArgumentException
                 (assertions/called-with-args? target/foo [])))))

(deftest called-once-with-args?-works
  (testing "an assertion for calling a spy once with args"
    (bond/with-spy [target/foo]
      (target/foo 1)
      (is (assertions/called-once-with-args? target/foo [1]))
      (is (not (assertions/called-once-with-args? target/foo [2])))))

  (testing "an assertion for calling a spy twice with args"
    (bond/with-spy [target/foo]
      (target/foo 1)
      (target/foo 2)
      (is (not (assertions/called-once-with-args? target/foo [1])))
      (is (not (assertions/called-once-with-args? target/foo [2])))))

  (testing "an assertion for calling a spy indirectly once with args"
    (bond/with-spy [target/foo]
      (target/foo-caller 1)
      (is (assertions/called-once-with-args? target/foo [1]))
      (is (not (assertions/called-once-with-args? target/foo [2])))))

  (testing "an assertion for a spy that was not called"
    (bond/with-spy [target/foo]
      (is (not (assertions/called-once-with-args? target/foo [])))))

  (testing "called-once-with-args? fails when its argument is not spied"
    (is (thrown? IllegalArgumentException
                 (assertions/called-once-with-args? target/foo [])))))

(deftest called-at-least-once-with-args?-works
  (testing "an assertion for calling a spy multiple times"
    (bond/with-spy [target/foo]
      (target/foo 1)
      (target/foo 2)
      (is (assertions/called-at-least-once-with-args? target/foo [1]))
      (is (assertions/called-at-least-once-with-args? target/foo [2]))
      (is (not (assertions/called-at-least-once-with-args? target/foo [3])))))

  (testing "an assertion for calling a spy multiple times with the same value"
    (bond/with-spy [target/foo]
      (target/foo 1)
      (target/foo 1)
      (is (assertions/called-at-least-once-with-args? target/foo [1]))
      (is (not (assertions/called-at-least-once-with-args? target/foo [2])))))

  (testing "an assertion for calling a spy once"
    (bond/with-spy [target/foo]
      (target/foo 1)
      (is (assertions/called-at-least-once-with-args? target/foo [1]))
      (is (not (assertions/called-at-least-once-with-args? target/foo [2])))))

  (testing "an assertion for a spy that was not called"
    (bond/with-spy [target/foo]
      (is (not (assertions/called-at-least-once-with-args? target/foo [])))))

  (testing "called-at-least-once-with-args? fails when its argument is not spied"
    (is (thrown? IllegalArgumentException
                 (assertions/called-at-least-once-with-args? target/foo [])))))
