#!/usr/bin/env bb
;; Parent version ranges, resolved as Maven's DefaultModelResolver does: the
;; highest version the repository metadata lists within the range.
;; Run: ./bb -cp resources/src/babashka script/mvn_oracle/parent_range_test.clj
(ns parent-range-test
  (:require [babashka.classpath :as cp]
            [babashka.deps :as deps]
            [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]))

;; add-deps resolves earlier libs again, so all tests share one repository
(def ^:private tmp (fs/create-temp-dir))
(def ^:private remote (fs/file tmp "remote"))
(def ^:private local (fs/file tmp "local"))

(defn- pom [& body]
  (str "<project><modelVersion>4.0.0</modelVersion>" (apply str body) "</project>"))

(defn- publish!
  "Writes ranged/<artifact> <version> with pom-text, and a jar unless jar? is false."
  [artifact version pom-text jar?]
  (let [d (fs/file remote "ranged" artifact version)]
    (fs/create-dirs d)
    (spit (fs/file d (str artifact "-" version ".pom")) pom-text)
    (when jar? (spit (fs/file d (str artifact "-" version ".jar")) "PK"))))

(doseq [v ["1.0" "2.0" "3.0"]]
  (publish! "parent" v
            (pom "<groupId>ranged</groupId><artifactId>parent</artifactId><version>" v "</version><packaging>pom</packaging>"
                 "<dependencyManagement><dependencies>"
                 "<dependency><groupId>ranged</groupId><artifactId>dep</artifactId><version>" v "</version></dependency>"
                 "</dependencies></dependencyManagement>")
            false)
  (publish! "dep" v (pom "<groupId>ranged</groupId><artifactId>dep</artifactId><version>" v "</version>") true))

(spit (fs/file remote "ranged" "parent" "maven-metadata.xml")
      (str "<metadata><groupId>ranged</groupId><artifactId>parent</artifactId><versioning>"
           "<release>3.0</release><versions><version>1.0</version><version>2.0</version><version>3.0</version></versions>"
           "</versioning></metadata>"))

(defn- child!
  "Publishes ranged/<artifact> 1.0, whose parent version is range and whose
  dependency on ranged/dep takes its version from the parent."
  [artifact range]
  (publish! artifact "1.0"
            (pom "<parent><groupId>ranged</groupId><artifactId>parent</artifactId><version>" range "</version></parent>"
                 "<artifactId>" artifact "</artifactId><version>1.0</version>"
                 "<dependencies><dependency><groupId>ranged</groupId><artifactId>dep</artifactId></dependency></dependencies>")
            true))

(defn- add! [artifact]
  (deps/add-deps {:deps {(symbol "ranged" artifact) {:mvn/version "1.0"}}
                  :mvn/repos {"remote" {:url (str (.toURI remote))}}
                  :mvn/local-repo (str local)
                  :deps-resolver :bb}
                 {:force true}))

(defn- failure [artifact]
  (try (add! artifact) nil
       (catch Exception e (ex-message e))))

(deftest highest-in-range-test
  (testing "the parent is the highest version the metadata lists within the range"
    (child! "in-range" "[1.0,3.0)")
    (add! "in-range")
    (is (some #(str/ends-with? % "dep-2.0.jar") (cp/split-classpath (cp/get-classpath))))))

(deftest no-match-test
  (testing "a range no listed version satisfies fails in Maven's words"
    (child! "no-match" "[5.0,6.0)")
    (is (= "No versions matched the requested parent version range '[5.0,6.0)'" (failure "no-match")))))

(deftest no-upper-bound-test
  (testing "a range without an upper bound fails in Maven's words"
    (child! "unbounded" "[1.0,)")
    (is (= "The requested parent version range '[1.0,)' does not specify an upper bound" (failure "unbounded")))))

(let [{:keys [fail error]} (t/run-tests 'parent-range-test)]
  (fs/delete-tree tmp)
  (System/exit (if (zero? (+ fail error)) 0 1)))
