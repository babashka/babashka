#!/usr/bin/env bb
;; babashka.impl.mvn.metadata's snapshot resolution against
;; maven-resolver-provider's DefaultVersionResolverTest (Maven 3.9.16): two
;; classifiers of one -SNAPSHOT version resolve to their own timestamped
;; builds, from the test's maven-metadata.xml served by a file: repository.
;; Run: ./bb -cp resources/src/babashka script/mvn_oracle/snapshot_test.clj
(ns snapshot-test
  (:require [babashka.fs :as fs]
            [babashka.impl.mvn.metadata :as metadata]
            [babashka.impl.mvn.repo :as repo]
            [clojure.test :as t :refer [deftest is testing]]))

(def metadata-xml
  "<metadata modelVersion=\"1.1.0\">
  <groupId>org.apache.maven.its</groupId>
  <artifactId>dep-mng5324</artifactId>
  <version>07.20.3-SNAPSHOT</version>
  <versioning>
    <snapshot><timestamp>20120809.112920</timestamp><buildNumber>97</buildNumber></snapshot>
    <lastUpdated>20120809112920</lastUpdated>
    <snapshotVersions>
      <snapshotVersion><classifier>classifierA</classifier><extension>jar</extension><value>07.20.3-20120809.112124-88</value><updated>20120809112124</updated></snapshotVersion>
      <snapshotVersion><classifier>classifierB</classifier><extension>jar</extension><value>07.20.3-20120809.112920-97</value><updated>20120809112920</updated></snapshotVersion>
    </snapshotVersions>
  </versioning>
</metadata>")

(def dir (fs/create-temp-dir))
(def remote (fs/file dir "remote"))
(def local (str (fs/file dir "local")))

(defn- artifact [classifier version]
  {:group "org.apache.maven.its" :artifact "dep-mng5324" :version version :classifier classifier :extension "jar"})

(deftest default-version-resolver-test
  (let [meta-dir (fs/file remote "org/apache/maven/its/dep-mng5324/07.20.3-SNAPSHOT")
        _ (fs/create-dirs meta-dir)
        _ (spit (fs/file meta-dir "maven-metadata.xml") metadata-xml)
        test-repo (repo/remote-repo {} ["test" {:url (str (.toURI (fs/file remote)))}])]
    (testing "testResolveSeparateInstalledClassifiedNonUniqueVersionedArtifacts"
      (is (= "dep-mng5324-07.20.3-20120809.112920-97-classifierB.jar"
             (metadata/snapshot-file-name local test-repo (artifact "classifierB" "07.20.3-SNAPSHOT"))))
      (is (= "dep-mng5324-07.20.3-20120809.112124-88-classifierA.jar"
             (metadata/snapshot-file-name local test-repo (artifact "classifierA" "07.20.3-SNAPSHOT")))))
    (testing "a classifier the metadata does not list gets the snapshot's own build"
      (is (= "dep-mng5324-07.20.3-20120809.112920-97-classifierC.jar"
             (metadata/snapshot-file-name local test-repo (artifact "classifierC" "07.20.3-SNAPSHOT")))))
    (testing "the metadata is cached in the local repository under the repository's id"
      (is (fs/exists? (fs/file local "org/apache/maven/its/dep-mng5324/07.20.3-SNAPSHOT/maven-metadata-test.xml"))))
    (testing "no metadata, no snapshot"
      (is (nil? (metadata/snapshot-file-name local test-repo (artifact nil "1.0-SNAPSHOT")))))))

(let [{:keys [fail error]} (t/run-tests 'snapshot-test)]
  (fs/delete-tree dir)
  (System/exit (if (zero? (+ fail error)) 0 1)))
