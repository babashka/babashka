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

(defn- err-of-add!
  "Adds ranged/<artifact> 1.0 and returns what was written to *err*."
  [artifact]
  (let [err (java.io.StringWriter.)]
    (binding [*err* err]
      (add! artifact))
    (str err)))

(defn- on-classpath? [jar]
  (some #(str/ends-with? % jar) (cp/split-classpath (cp/get-classpath))))

(def ^:private missing-dependency
  "A dependency no repository has. Resolving it fails."
  "<dependencies><dependency><groupId>nope</groupId><artifactId>nope</artifactId><version>1.0.0</version></dependency></dependencies>")

(deftest highest-in-range-test
  (testing "the parent is the highest version the metadata lists within the range"
    (child! "in-range" "[1.0,3.0)")
    (add! "in-range")
    (is (some #(str/ends-with? % "dep-2.0.jar") (cp/split-classpath (cp/get-classpath))))))

(deftest no-match-test
  (testing "a range that matches no listed version fails with Maven's message"
    (child! "no-match" "[5.0,6.0)")
    (is (= "No versions matched the requested parent version range '[5.0,6.0)'" (failure "no-match")))))

(deftest no-upper-bound-test
  (testing "an unbounded range fails with Maven's message"
    (child! "unbounded" "[1.0,)")
    (is (= "The requested parent version range '[1.0,)' does not specify an upper bound" (failure "unbounded")))))

(deftest constant-version-test
  (testing "a POM without a version under a parent version range resolves without its dependencies"
    (publish! "no-version" "1.0"
              (pom "<parent><groupId>ranged</groupId><artifactId>parent</artifactId><version>[1.0,3.0)</version></parent>"
                   "<artifactId>no-version</artifactId>" missing-dependency)
              true)
    (is (str/includes? (err-of-add! "no-version")
                       "The POM for ranged:no-version:1.0 is invalid, transitive dependencies will not be available: Version must be a constant"))
    (is (on-classpath? "no-version-1.0.jar")))
  (testing "a POM whose version is ${project.version} under a parent version range resolves without its dependencies"
    (publish! "project-version" "1.0"
              (pom "<parent><groupId>ranged</groupId><artifactId>parent</artifactId><version>[1.0,3.0)</version></parent>"
                   "<artifactId>project-version</artifactId><version>${project.version}</version>" missing-dependency)
              true)
    (is (str/includes? (err-of-add! "project-version")
                       "The POM for ranged:project-version:1.0 is invalid, transitive dependencies will not be available: Version must be a constant"))
    (is (on-classpath? "project-version-1.0.jar"))))

(deftest installed-parent-test
  (testing "a parent listed only in the local repository's maven-metadata-local.xml resolves"
    (let [dir (fs/file local "ranged" "installed-parent")]
      (fs/create-dirs (fs/file dir "2.0"))
      (spit (fs/file dir "2.0" "installed-parent-2.0.pom")
            (pom "<groupId>ranged</groupId><artifactId>installed-parent</artifactId><version>2.0</version><packaging>pom</packaging>"
                 "<dependencyManagement><dependencies>"
                 "<dependency><groupId>ranged</groupId><artifactId>installed-dep</artifactId><version>1.0</version></dependency>"
                 "</dependencies></dependencyManagement>"))
      (spit (fs/file dir "maven-metadata-local.xml")
            (str "<metadata><groupId>ranged</groupId><artifactId>installed-parent</artifactId><versioning>"
                 "<versions><version>2.0</version></versions>"
                 "</versioning></metadata>")))
    (publish! "installed-dep" "1.0" (pom "<groupId>ranged</groupId><artifactId>installed-dep</artifactId><version>1.0</version>") true)
    (publish! "installed-child" "1.0"
              (pom "<parent><groupId>ranged</groupId><artifactId>installed-parent</artifactId><version>[1.0,3.0)</version></parent>"
                   "<artifactId>installed-child</artifactId><version>1.0</version>"
                   "<dependencies><dependency><groupId>ranged</groupId><artifactId>installed-dep</artifactId></dependency></dependencies>")
              true)
    (add! "installed-child")
    (is (on-classpath? "installed-dep-1.0.jar"))))

(let [{:keys [fail error]} (t/run-tests 'parent-range-test)]
  (fs/delete-tree tmp)
  (System/exit (if (zero? (+ fail error)) 0 1)))
