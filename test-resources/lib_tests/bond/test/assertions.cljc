(ns bond.test.assertions
  (:require #?(:clj [clojure.test :refer (deftest is testing)])
            [bond.assertions :as assertions]
            [bond.james :as bond :include-macros true]
            [bond.test.target :as target])
  #?(:cljs (:require-macros [cljs.test :refer (is deftest testing)])))

(deftest called?-works
  (testing "a spy was called directly"
    (bond/with-spy [target/foo]
      (let [_ (target/foo 1)]
        (is (assertions/called? target/foo)))))

  (testing "a spy was called indirectly"
    (bond/with-spy [target/foo]
      (let [_ (target/foo-caller 1)]
        (is (assertions/called? target/foo)))))

  (testing "a spy was not called"
    (bond/with-spy [target/foo]
      (is (not (assertions/called? target/foo))))))

(deftest called-times?-works
  (testing "the number of times a spy was called"
    (bond/with-spy [target/foo]
      (let [_ (target/foo-caller 1)]
        (is (assertions/called-times? target/foo 1 )))
      (let [_ (target/foo 2)]
        (is (assertions/called-times? target/foo 2)))))

  (testing "the number of times a spy was not called"
    (bond/with-spy [target/foo]
      (let [_ (target/foo-caller 1)]
        (is (not (assertions/called-times? target/foo 2))))
      (let [_ (target/foo-caller 2)]
        (is (not (assertions/called-times? target/foo 1)))))))

(deftest called-with-args?-works
  (testing "an assertion for calling a spy with args"
    (bond/with-spy [target/foo]
      (let [_ (target/foo-caller 1)]
        (is (assertions/called-with-args? target/foo [[1]]))
        (is (not (assertions/called-with-args? target/foo [[2]])))
        (is (not (assertions/called-with-args? target/bar [[1]])))
        (is (not (assertions/called-with-args? target/foo [[1 2]]))))))

  (testing "an assertion for calling a spy multiple times with args"
    (bond/with-spy [target/foo]
      (let [_ (do (target/foo-caller 1)
                  (target/foo-caller 2))]
        (is (assertions/called-with-args? target/foo [[1] [2]]))))))
