#!/usr/bin/env bb
;; babashka.impl.mvn.metadata's refresh decision against maven-resolver's
;; DefaultUpdatePolicyAnalyzerTest (1.9.27), on a cached metadata file's
;; modification time.
;; Run: ./bb -cp resources/src/babashka script/mvn_oracle/update_policy_test.clj
(ns update-policy-test
  (:require [babashka.fs :as fs]
            [babashka.impl.mvn.metadata :as metadata]
            [clojure.test :as t :refer [deftest is testing]]))

(def dir (fs/create-temp-dir))

(defn- stale?
  "The decision for a file last modified at `millis`."
  [millis policy]
  (let [f (fs/file dir (str (random-uuid) ".xml"))]
    (spit f "")
    (fs/set-last-modified-time f millis)
    (#'metadata/stale? f {:update policy})))

(defn- now [] (System/currentTimeMillis))
(def local-midnight (#'metadata/local-midnight-millis))

(deftest update-policy-test
  (testing "a file that is not there"
    (is (#'metadata/stale? (fs/file dir "missing.xml") {:update :never})))
  (testing "testIsUpdateRequiredPolicyNever"
    (is (not (stale? 0 :never)))
    (is (not (stale? (- (now) 604800000) :never))))
  (testing "testIsUpdateRequiredPolicyAlways"
    (is (stale? (now) :always))
    (is (stale? (- (now) 1000) :always)))
  (testing "testIsUpdateRequiredPolicyDaily"
    (is (stale? 0 :daily))
    (is (not (stale? (now) :daily)))
    (is (not (stale? local-midnight :daily)))
    (is (not (stale? (+ local-midnight 1000) :daily)))
    (is (stale? (- local-midnight 1000) :daily)))
  (testing "testIsUpdateRequiredPolicyInterval"
    (is (stale? 0 5))
    (is (not (stale? (now) 5)))
    (is (not (stale? (- (now) 5000) 5)))
    (is (stale? (- (now) (* 1000 60 5) 1000) 5))))

(let [{:keys [fail error]} (t/run-tests 'update-policy-test)]
  (fs/delete-tree dir)
  (System/exit (if (zero? (+ fail error)) 0 1)))
