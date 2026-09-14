#!/usr/bin/env bb
;; :local/root projects with a pom.xml whose parents are on disk.
;; Run: ./bb -cp resources/src/babashka script/mvn_oracle/local_pom_test.clj
(ns local-pom-test
  (:require [babashka.classpath :as cp]
            [babashka.deps :as deps]
            [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]))

(defn- pom [& body]
  (str "<project><modelVersion>4.0.0</modelVersion>" (apply str body) "</project>"))

(defn- file-repo!
  "A file: repository with example/<artifact> 1.0.0. Returns its URL."
  [dir artifact]
  (let [d (fs/file dir "example" artifact "1.0.0")]
    (fs/create-dirs d)
    (spit (fs/file d (str artifact "-1.0.0.pom"))
          (pom "<groupId>example</groupId><artifactId>" artifact "</artifactId><version>1.0.0</version>"))
    (spit (fs/file d (str artifact "-1.0.0.jar")) "PK")
    ;; a file URL the way java writes one, so it holds on Windows too
    (str (.toURI (fs/file dir)))))

(defn- on-classpath? [jar]
  (some #(str/ends-with? % jar) (cp/split-classpath (cp/get-classpath))))

;; add-deps keeps resolving the libs it already added, so the tests share one
;; repository, the projects stay on disk until every test has run, and each
;; test uses its own names
(def ^:private tmp (fs/create-temp-dir))

(def ^:private repos
  {"local" {:url (do (file-repo! (fs/file tmp "repo") "managed")
                     (file-repo! (fs/file tmp "repo") "profiled"))}})

(def ^:private local-repo (str (fs/file tmp "m2")))

(deftest grandparent-on-disk-test
  (testing "each parent's relativePath resolves from the directory of the POM that declares it"
    (let [dir (fs/file tmp "grandparent")
          root (fs/file dir "project")
          child (fs/file root "parent" "child")]
      (fs/create-dirs child)
      (spit (fs/file root "pom.xml")
            (pom "<groupId>example</groupId><artifactId>grandparent</artifactId><version>1</version><packaging>pom</packaging>"
                 "<dependencyManagement><dependencies>"
                 "<dependency><groupId>example</groupId><artifactId>managed</artifactId><version>1.0.0</version></dependency>"
                 "</dependencies></dependencyManagement>"))
      (spit (fs/file root "parent" "pom.xml")
            (pom "<parent><groupId>example</groupId><artifactId>grandparent</artifactId><version>1</version></parent>"
                 "<artifactId>parent</artifactId><packaging>pom</packaging>"))
      (spit (fs/file child "pom.xml")
            (pom "<parent><groupId>example</groupId><artifactId>parent</artifactId><version>1</version></parent>"
                 "<artifactId>child</artifactId>"
                 "<dependencies><dependency><groupId>example</groupId><artifactId>managed</artifactId></dependency></dependencies>"))
      (deps/add-deps {:deps {'example/child {:local/root (str child)}}
                      :mvn/repos repos
                      :mvn/local-repo local-repo
                      :deps-resolver :bb}
                     {:force true})
      (is (on-classpath? "managed-1.0.0.jar")))))

(deftest parent-profile-on-disk-test
  (testing "a file activation in a parent on disk checks the parent's directory"
    (let [dir (fs/file tmp "profile")
          parent (fs/file dir "project" "parent")
          child (fs/file parent "child")]
      (fs/create-dirs child)
      (spit (fs/file parent "marker") "")
      (spit (fs/file parent "pom.xml")
            (pom "<groupId>example</groupId><artifactId>profile-parent</artifactId><version>1</version><packaging>pom</packaging>"
                 "<profiles><profile><id>marker</id><activation><file><exists>marker</exists></file></activation>"
                 "<dependencies><dependency><groupId>example</groupId><artifactId>profiled</artifactId><version>1.0.0</version></dependency></dependencies>"
                 "</profile></profiles>"))
      (spit (fs/file child "pom.xml")
            (pom "<parent><groupId>example</groupId><artifactId>profile-parent</artifactId><version>1</version></parent>"
                 "<artifactId>profile-child</artifactId>"))
      (deps/add-deps {:deps {'example/profile-child {:local/root (str child)}}
                      :mvn/repos repos
                      :mvn/local-repo local-repo
                      :deps-resolver :bb}
                     {:force true})
      (is (on-classpath? "profiled-1.0.0.jar")))))

(let [{:keys [fail error]} (t/run-tests 'local-pom-test)]
  (fs/delete-tree tmp)
  (System/exit (if (zero? (+ fail error)) 0 1)))
