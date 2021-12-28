(ns expound.problems-test
  (:require [clojure.test :as ct :refer [is deftest use-fixtures]]
            [clojure.spec.alpha :as s]
            [expound.problems :as problems]
            [expound.test-utils :as test-utils]))

(use-fixtures :once
  test-utils/check-spec-assertions
  test-utils/instrument-all)

(s/def :highlighted-value/nested-map-of (s/map-of keyword? (s/map-of keyword? keyword?)))

(s/def :highlighted-value/city string?)
(s/def :highlighted-value/address (s/keys :req-un [:highlighted-value/city]))
(s/def :highlighted-value/house (s/keys :req-un [:highlighted-value/address]))

(s/def :annotate-test/div-fn (s/fspec
                              :args (s/cat :x int? :y pos-int?)))
(defn my-div [x y]
  (assert (pos? (/ x y))))

(deftest annotate-test
  (is (= {:expound/in [0]
          :val '(0 1)
          :reason "Assert failed: (pos? (/ x y))"}
         (-> (s/explain-data (s/coll-of :annotate-test/div-fn) [my-div])
             problems/annotate
             :expound/problems
             first
             (select-keys [:expound/in :val :reason])))))