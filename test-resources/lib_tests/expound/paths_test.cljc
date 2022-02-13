(ns expound.paths-test
  (:require [clojure.test :as ct :refer [is deftest use-fixtures]]
            [clojure.test.check.generators :as gen]
            [com.gfredericks.test.chuck.clojure-test :refer [checking]]
            [expound.paths :as paths]
            [expound.test-utils :as test-utils]
            [com.gfredericks.test.chuck :as chuck]))

(def num-tests 100)

(use-fixtures :once
  test-utils/check-spec-assertions
  test-utils/instrument-all)

(deftest compare-paths-test
  (checking
   "path to a key comes before a path to a value"
   10
   [k gen/simple-type-printable]
   (is (= -1 (paths/compare-paths [(paths/->KeyPathSegment k)] [k])))
   (is (= 1 (paths/compare-paths [k] [(paths/->KeyPathSegment k)])))))

(defn nth-value [form i]
  (let [seq (remove map-entry? (tree-seq coll? seq form))]
    (nth seq (mod i (count seq)))))

(deftest paths-to-value-test
  (checking
   "value-in is inverse of paths-to-value"
   (chuck/times num-tests)
   [form test-utils/any-printable-wo-nan
    i gen/nat
    :let [x (nth-value form i)
          paths (paths/paths-to-value form x [] [])]]
   (is (seq paths))
   (doseq [path paths]
     (is (= x
            (paths/value-in form
                            path))))))
