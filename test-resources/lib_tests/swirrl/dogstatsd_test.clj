(ns swirrl.dogstatsd-test
  (:require [swirrl.dogstatsd :as sut]
            [swirrl.dogstatsd.specs]
            [clojure.test :refer [deftest is testing]]
            [clojure.spec.test.alpha :as st]))

(st/instrument)

(deftest basic-invocation-tests
  (testing "Basic metric procedure calls run without error"
    (let [client (sut/configure {:endpoint "localhost:8111"})]

      (sut/increment! client ::increment)
      (sut/increment! client ::increment 10)
      (sut/decrement! client ::decrement)

      (sut/histogram! client ::histogram 10)
      (sut/distribution! client ::distribution 10)
      (sut/set! client ::set "a-value")
      (sut/event! client "event title" "some text here" {})

      (is true))))
