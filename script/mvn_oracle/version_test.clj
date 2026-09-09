#!/usr/bin/env bb
;; babashka.mvn.version against maven-resolver-util's GenericVersionTest,
;; the scheme tools.deps compares with. Cases in version-cases.edn.
;; Run: ./bb -cp resources/src/babashka script/mvn_oracle/version_test.clj
(ns version-test
  (:require [babashka.mvn.version :as version]
            [clojure.edn :as edn]
            [clojure.test :as t :refer [deftest is testing]]))

(def cases (edn/read-string (slurp "script/mvn_oracle/version-cases.edn")))

(defn- sign [a b]
  (let [c (version/compare-versions a b)]
    (cond (neg? c) :lt (pos? c) :gt :else :eq)))

(def ^:private flipped {:lt :gt :gt :lt :eq :eq})

(deftest order-test
  (doseq [[op a b] cases
          :when (not= :sequence op)]
    (testing (pr-str [op a b])
      (is (= op (sign a b)))
      (is (= (flipped op) (sign b a))))))

(deftest sequence-test
  (doseq [c cases
          :when (= :sequence (first c))
          [a b] (partition 2 1 (rest c))]
    (testing (pr-str [a b])
      (is (= :lt (sign a b)))
      (is (= :gt (sign b a))))))

(deftest range-test
  (testing "the bounds tools.deps hands over"
    (is (version/in-range? "2.4.1" "[2.4,2.5)"))
    (is (not (version/in-range? "2.5.0" "[2.4,2.5)")))
    (is (version/in-range? "2.5.0" "[2.4,2.5]"))
    (is (not (version/in-range? "2.4" "(2.4,)")))
    (is (version/in-range? "99" "(2.4,)"))
    (is (version/in-range? "1.0-SNAPSHOT" "[1.0-alpha,1.0]"))
    (is (version/in-range? "2.0" "[1.0],[2.0,)"))
    (is (not (version/in-range? "1.5" "[1.0],[2.0,)")))))

(let [{:keys [fail error]} (t/run-tests 'version-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
