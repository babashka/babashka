#!/usr/bin/env bb
;; babashka.impl.mvn.version's ranges against maven-resolver-util's
;; GenericVersionRangeTest and GenericVersionSchemeTest (1.9.27).
;; Run: ./bb -cp resources/src/babashka script/mvn_oracle/range_test.clj
(ns range-test
  (:require [babashka.impl.mvn.version :as version]
            [clojure.test :as t :refer [deftest is testing]]))

(defn- contains-all? [range & versions]
  (doseq [v versions] (is (version/in-range? v range) (str range " should contain " v))))
(defn- contains-none? [range & versions]
  (doseq [v versions] (is (not (version/in-range? v range)) (str range " should not contain " v))))
(defn- invalid? [range]
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid version range" (version/in-range? "1" range))
      (str range " should be invalid")))

(deftest generic-version-range-test
  (testing "testLowerBoundInclusiveUpperBoundInclusive"
    (contains-all? "[1,2]" "1" "1.1-SNAPSHOT" "2"))
  (testing "testLowerBoundInclusiveUpperBoundExclusive"
    (contains-all? "[1.2.3.4.5,1.2.3.4.6)" "1.2.3.4.5")
    (contains-none? "[1.2.3.4.5,1.2.3.4.6)" "1.2.3.4.6"))
  (testing "testLowerBoundExclusiveUpperBoundInclusive"
    (contains-none? "(1a,1b]" "1a")
    (contains-all? "(1a,1b]" "1b"))
  (testing "testLowerBoundExclusiveUpperBoundExclusive"
    (contains-none? "(1,3)" "1" "3")
    (contains-all? "(1,3)" "2-SNAPSHOT"))
  (testing "testSingleVersion"
    (contains-all? "[1]" "1")
    (contains-all? "[1,1]" "1"))
  (testing "testSingleWildcardVersion"
    (contains-all? "[1.2.*]" "1.2-alpha-1" "1.2-SNAPSHOT" "1.2" "1.2.9999999")
    (contains-none? "[1.2.*]" "1.3-rc-1"))
  (testing "testMissingOpenDelimiter: not a range, a plain version to the scheme"
    (contains-all? "1.0]" "1.0]")
    (contains-none? "1.0)" "1.0"))
  (testing "testMissingCloseDelimiter"
    (invalid? "[1.0")
    (invalid? "(1.0"))
  (testing "testTooManyVersions"
    (invalid? "[1,2,3]")
    (invalid? "(1,2,3)")
    (invalid? "[1,2,3)"))
  (testing "a single version needs []"
    (invalid? "(1.0)")
    (invalid? "(1.0]"))
  (testing "a lower bound above the upper bound"
    (invalid? "[2,1]")))

(deftest generic-version-scheme-test
  (testing "testEnumeratedVersions"
    (contains-all? "1.0" "1.0")
    (contains-all? "[1.0]" "1.0")
    (contains-all? "[1.0],[2.0]" "1.0" "2.0")
    (contains-all? "[1.0],[2.0],[3.0]" "1.0" "2.0" "3.0")
    (contains-none? "[1.0],[2.0],[3.0]" "1.5")
    (contains-all? "[1,3),(3,5)" "1" "2" "4")
    (contains-none? "[1,3),(3,5)" "3" "5")
    (contains-all? "[1,3),(3,)" "1" "2" "4")
    (contains-none? "[1,3),(3,)" "3"))
  (testing "testInvalid"
    (invalid? "[1,")
    (invalid? "[1,2],(3,")
    (invalid? "[1,2],3"))
  (testing "testSameUpperAndLowerBound"
    (contains-all? "[1.0,1.0]" "1.0")))

(let [{:keys [fail error]} (t/run-tests 'range-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
