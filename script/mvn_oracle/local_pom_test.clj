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
                     (file-repo! (fs/file tmp "repo") "unprofiled")
                     (file-repo! (fs/file tmp "repo") "ranged")
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

(defn- profile-project!
  "A parent with a profile activated by a marker file, adding example/<dep>,
  and a child below it. Returns the child's directory."
  [dir id dep]
  (let [parent (fs/file dir "project" "parent")
        child (fs/file parent "child")]
    (fs/create-dirs child)
    (spit (fs/file parent "pom.xml")
          (pom "<groupId>example</groupId><artifactId>" id "-parent</artifactId><version>1</version><packaging>pom</packaging>"
               "<profiles><profile><id>marker</id><activation><file><exists>marker</exists></file></activation>"
               "<dependencies><dependency><groupId>example</groupId><artifactId>" dep "</artifactId><version>1.0.0</version></dependency></dependencies>"
               "</profile></profiles>"))
    (spit (fs/file child "pom.xml")
          (pom "<parent><groupId>example</groupId><artifactId>" id "-parent</artifactId><version>1</version></parent>"
               "<artifactId>" id "-child</artifactId>"))
    child))

(deftest parent-profile-activation-test
  (testing "a parent's file activation checks the directory of the project being built"
    (let [child (profile-project! (fs/file tmp "profile-on") "profile-on" "profiled")]
      (spit (fs/file child "marker") "")
      (deps/add-deps {:deps {'example/profile-on-child {:local/root (str child)}}
                      :mvn/repos repos
                      :mvn/local-repo local-repo
                      :deps-resolver :bb}
                     {:force true})
      (is (on-classpath? "profiled-1.0.0.jar"))))
  (testing "a marker only next to the parent leaves the profile off"
    (let [child (profile-project! (fs/file tmp "profile-off") "profile-off" "unprofiled")]
      (spit (fs/file (fs/parent child) "marker") "")
      (deps/add-deps {:deps {'example/profile-off-child {:local/root (str child)}}
                      :mvn/repos repos
                      :mvn/local-repo local-repo
                      :deps-resolver :bb}
                     {:force true})
      (is (not (on-classpath? "unprofiled-1.0.0.jar"))))))

(deftest parent-range-on-disk-test
  (testing "a parent on disk whose version is in the parent's range is used without the repositories"
    (let [parent (fs/file tmp "range" "project")
          child (fs/file parent "child")]
      (fs/create-dirs child)
      (spit (fs/file parent "pom.xml")
            (pom "<groupId>example</groupId><artifactId>range-parent</artifactId><version>2</version><packaging>pom</packaging>"
                 "<dependencyManagement><dependencies>"
                 "<dependency><groupId>example</groupId><artifactId>ranged</artifactId><version>1.0.0</version></dependency>"
                 "</dependencies></dependencyManagement>"))
      (spit (fs/file child "pom.xml")
            (pom "<parent><groupId>example</groupId><artifactId>range-parent</artifactId><version>[1,3)</version></parent>"
                 "<artifactId>range-child</artifactId><version>1</version>"
                 "<dependencies><dependency><groupId>example</groupId><artifactId>ranged</artifactId></dependency></dependencies>"))
      (deps/add-deps {:deps {'example/range-child {:local/root (str child)}}
                      :mvn/repos repos
                      :mvn/local-repo local-repo
                      :deps-resolver :bb}
                     {:force true})
      (is (on-classpath? "ranged-1.0.0.jar")))))

(let [{:keys [fail error]} (t/run-tests 'local-pom-test)]
  (fs/delete-tree tmp)
  (System/exit (if (zero? (+ fail error)) 0 1)))
