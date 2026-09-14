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
  "A file: repository with example/managed 1.0.0. Returns its URL."
  [dir]
  (let [d (fs/file dir "example" "managed" "1.0.0")]
    (fs/create-dirs d)
    (spit (fs/file d "managed-1.0.0.pom")
          (pom "<groupId>example</groupId><artifactId>managed</artifactId><version>1.0.0</version>"))
    (spit (fs/file d "managed-1.0.0.jar") "PK")
    ;; a file URL the way java writes one, so it holds on Windows too
    (str (.toURI (fs/file dir)))))

(deftest grandparent-on-disk-test
  (testing "each parent's relativePath resolves from the directory of the POM that declares it"
    (fs/with-temp-dir [dir {}]
      (let [root (fs/file dir "project")
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
                        :mvn/repos {"local" {:url (file-repo! (fs/file dir "repo"))}}
                        :mvn/local-repo (str (fs/file dir "m2"))
                        :deps-resolver :bb}
                       {:force true})
        (is (some #(str/ends-with? % "managed-1.0.0.jar")
                  (cp/split-classpath (cp/get-classpath))))))))

(let [{:keys [fail error]} (t/run-tests 'local-pom-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
