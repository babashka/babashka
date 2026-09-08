#!/usr/bin/env bb
;; babashka.mvn.http on file: repositories: encoded and bare paths, misses.
;; Run: ./bb -cp resources/src/babashka script/mvn_oracle/http_test.clj
(ns http-test
  (:require [babashka.fs :as fs]
            [babashka.mvn.http :as http]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]))

(deftest file-url-test
  (fs/with-temp-dir [dir {}]
    (let [repo (fs/file dir "my repo")
          f (fs/file repo "a" "b.pom")]
      (fs/create-dirs (fs/parent f))
      (spit f "<project/>")
      (testing "a well-formed URL with the space encoded"
        (is (= "<project/>" (http/fetch (str (.toURI (fs/file repo)) "a/b.pom") {}))))
      (testing "the same URL with a bare space, as a user types it in :mvn/repos"
        (is (= "<project/>" (http/fetch (str "file://" repo "/a/b.pom") {}))))
      (testing "a miss is nil, not an error"
        (is (nil? (http/fetch (str "file://" repo "/a/nope.pom") {}))))
      (testing "download! keeps the encoding too"
        (let [dest (fs/file dir "out.pom")]
          (is (= (str dest) (http/download! (str (.toURI (fs/file repo)) "a/b.pom") dest {:checksum :ignore})))
          (is (str/includes? (slurp dest) "project")))))))

(let [{:keys [fail error]} (t/run-tests 'http-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
