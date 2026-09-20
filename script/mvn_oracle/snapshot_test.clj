#!/usr/bin/env bb
;; babashka.impl.mvn.metadata's snapshot resolution against
;; maven-resolver-provider's DefaultVersionResolverTest (Maven 3.9.16): the
;; newest build per classifier across the local repository's metadata and
;; the repositories', from a maven-metadata.xml served by a file: repository.
;; Run: ./bb -cp resources/src/babashka script/mvn_oracle/snapshot_test.clj
(ns snapshot-test
  (:require [babashka.fs :as fs]
            [babashka.impl.mvn.metadata :as metadata]
            [babashka.impl.mvn.repo :as repo]
            [clojure.string :as str]
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

(defn- local-metadata-xml [updated]
  (str "<metadata modelVersion=\"1.1.0\"><groupId>org.apache.maven.its</groupId><artifactId>dep-mng5324</artifactId>"
       "<versioning><lastUpdated>" updated "</lastUpdated><snapshot><localCopy>true</localCopy></snapshot>"
       "<snapshotVersions><snapshotVersion><extension>jar</extension><value>07.20.3-SNAPSHOT</value><updated>" updated "</updated></snapshotVersion></snapshotVersions>"
       "</versioning><version>07.20.3-SNAPSHOT</version></metadata>"))

(def dir (fs/create-temp-dir))
(def remote (fs/file dir "remote"))

(defn- artifact [classifier version]
  {:group "org.apache.maven.its" :artifact "dep-mng5324" :version version :classifier classifier :extension "jar"})

(defn- file-name [local repo artifact]
  (let [{:keys [version]} (metadata/resolve-snapshot local [repo] artifact)]
    (str "dep-mng5324-" version (when-let [c (:classifier artifact)] (str "-" c)) ".jar")))

(deftest default-version-resolver-test
  (let [local (str (fs/file dir "local"))
        meta-dir (fs/file remote "org/apache/maven/its/dep-mng5324/07.20.3-SNAPSHOT")
        _ (fs/create-dirs meta-dir)
        _ (spit (fs/file meta-dir "maven-metadata.xml") metadata-xml)
        test-repo (repo/remote-repo {} ["test" {:url (str (.toURI (fs/file remote)))}])]
    (testing "testResolveSeparateInstalledClassifiedNonUniqueVersionedArtifacts"
      (is (= "dep-mng5324-07.20.3-20120809.112920-97-classifierB.jar"
             (file-name local test-repo (artifact "classifierB" "07.20.3-SNAPSHOT"))))
      (is (= "dep-mng5324-07.20.3-20120809.112124-88-classifierA.jar"
             (file-name local test-repo (artifact "classifierA" "07.20.3-SNAPSHOT")))))
    (testing "a classifier the metadata does not list resolves to the base version"
      (is (= {:version "07.20.3-SNAPSHOT" :repo :none}
             (metadata/resolve-snapshot local [test-repo] (artifact "classifierC" "07.20.3-SNAPSHOT")))))
    (testing "the metadata is cached in the local repository under the repository's id"
      (is (fs/exists? (fs/file local "org/apache/maven/its/dep-mng5324/07.20.3-SNAPSHOT/maven-metadata-test.xml"))))
    (testing "without metadata the base version resolves from no repository"
      (is (= {:version "1.0-SNAPSHOT" :repo :none}
             (metadata/resolve-snapshot local [test-repo] (artifact nil "1.0-SNAPSHOT")))))))

(def remote-jar-metadata-xml
  "<metadata modelVersion=\"1.1.0\"><groupId>org.apache.maven.its</groupId><artifactId>dep-mng5324</artifactId><version>07.20.3-SNAPSHOT</version>
  <versioning><snapshot><timestamp>20120809.112920</timestamp><buildNumber>97</buildNumber></snapshot><lastUpdated>20120809112920</lastUpdated>
  <snapshotVersions><snapshotVersion><extension>jar</extension><value>07.20.3-20120809.112920-97</value><updated>20120809112920</updated></snapshotVersion></snapshotVersions>
  </versioning></metadata>")

(deftest local-copy-test
  (let [local (str (fs/file dir "local2"))
        local-dir (fs/file local "org/apache/maven/its/dep-mng5324/07.20.3-SNAPSHOT")
        remote2 (fs/file dir "remote2")
        meta-dir (fs/file remote2 "org/apache/maven/its/dep-mng5324/07.20.3-SNAPSHOT")
        _ (fs/create-dirs meta-dir)
        _ (spit (fs/file meta-dir "maven-metadata.xml") remote-jar-metadata-xml)
        test-repo (repo/remote-repo {} ["test" {:url (str (.toURI (fs/file remote2)))}])
        art (artifact nil "07.20.3-SNAPSHOT")]
    (fs/create-dirs local-dir)
    (testing "a local copy newer than the repository's build wins"
      (spit (fs/file local-dir "maven-metadata-local.xml") (local-metadata-xml "20120810000000"))
      (is (= {:version "07.20.3-SNAPSHOT" :repo nil} (metadata/resolve-snapshot local [test-repo] art))))
    (testing "a repository's build newer than the local copy wins"
      (spit (fs/file local-dir "maven-metadata-local.xml") (local-metadata-xml "20120801000000"))
      (is (= "07.20.3-20120809.112920-97" (:version (metadata/resolve-snapshot local [test-repo] art)))))
    (testing "a local copy alone resolves to the base version from the local repository"
      (spit (fs/file local-dir "maven-metadata-local.xml") (local-metadata-xml "20120801000000"))
      (is (= {:version "07.20.3-SNAPSHOT" :repo nil} (metadata/resolve-snapshot local [] art))))
    (testing "a build number in the local metadata is repaired to a local copy"
      (spit (fs/file local-dir "maven-metadata-local.xml")
            "<metadata><versioning><lastUpdated>20120810000000</lastUpdated><snapshot><timestamp>20120809.112920</timestamp><buildNumber>97</buildNumber></snapshot></versioning></metadata>")
      (is (= {:version "07.20.3-SNAPSHOT" :repo nil} (metadata/resolve-snapshot local [test-repo] art))))))

(deftest installed-snapshot-file-test
  (testing "an installed snapshot's jar resolves without repositories"
    (let [local (str (fs/file dir "local3"))
          version-dir (fs/file local "g/a/1.0-SNAPSHOT")
          art {:group "g" :artifact "a" :version "1.0-SNAPSHOT" :extension "jar"}]
      (fs/create-dirs version-dir)
      (spit (fs/file version-dir "a-1.0-SNAPSHOT.jar") "PK")
      (spit (fs/file version-dir "_remote.repositories") "a-1.0-SNAPSHOT.jar>=\n")
      (is (= (str (fs/file version-dir "a-1.0-SNAPSHOT.jar")) (repo/resolve-file! local [] art)))
      (testing "and with a repository that does not have it"
        (is (= (str (fs/file version-dir "a-1.0-SNAPSHOT.jar"))
               (repo/resolve-file! local [(repo/remote-repo {} ["test" {:url (str (.toURI (fs/file remote)))}])] art))))
      (testing "and with a repository that cannot be reached"
        (is (= (str (fs/file version-dir "a-1.0-SNAPSHOT.jar"))
               (repo/resolve-file! local [(repo/remote-repo {} ["down" {:url "https://127.0.0.1:1/"}])] art)))))))

(deftest unreachable-repository-test
  (let [local (str (fs/file dir "local-down"))
        version-dir (fs/file local "org/apache/maven/its/dep-mng5324/07.20.3-SNAPSHOT")
        down (repo/remote-repo {} ["down" {:url "https://127.0.0.1:1/" :snapshots {:update :always}}])]
    (testing "without cached metadata the base version resolves from no repository"
      (is (= {:version "07.20.3-SNAPSHOT" :repo :none}
             (metadata/resolve-snapshot local [down] (artifact "classifierA" "07.20.3-SNAPSHOT")))))
    (testing "stale cached metadata still names the build"
      (fs/create-dirs version-dir)
      (spit (fs/file version-dir "maven-metadata-down.xml") metadata-xml)
      (is (= "dep-mng5324-07.20.3-20120809.112124-88-classifierA.jar"
             (file-name local down (artifact "classifierA" "07.20.3-SNAPSHOT")))))
    (testing "the versions of an artifact come from the local repository alone"
      (spit (fs/file local "org/apache/maven/its/dep-mng5324/maven-metadata-local.xml")
            "<metadata><versioning><versions><version>07.20.3-SNAPSHOT</version></versions></versioning></metadata>")
      (is (= ["07.20.3-SNAPSHOT"]
             (:versions (metadata/versions local [down] {:group "org.apache.maven.its" :artifact "dep-mng5324"})))))))

(defn- sha1 [^String s]
  (apply str (map #(format "%02x" %) (.digest (java.security.MessageDigest/getInstance "SHA-1") (.getBytes s)))))

(defn- snapshot-repo
  "Writes a file: repository with id under root holding the jar metadata and
  builds, pairs of build and content. Returns the repository."
  [root id builds]
  (let [version-dir (fs/file root "org/apache/maven/its/dep-mng5324/07.20.3-SNAPSHOT")]
    (fs/create-dirs version-dir)
    (spit (fs/file version-dir "maven-metadata.xml") remote-jar-metadata-xml)
    (doseq [[build content] builds
            :let [jar (str "dep-mng5324-07.20.3-" build ".jar")]]
      (spit (fs/file version-dir jar) content)
      (spit (fs/file version-dir (str jar ".sha1")) (sha1 content)))
    (repo/remote-repo {} [id {:url (str (.toURI (fs/file root)))}])))

(deftest pinned-build-test
  (let [local (str (fs/file dir "local4"))
        test-repo (snapshot-repo (fs/file dir "remote4") "test"
                                 [["20120809.112124-88" "88"] ["20120809.112920-97" "9797"]])
        resolve (fn [version] (slurp (repo/resolve-file! local [test-repo] (artifact nil version))))]
    (testing "a pinned build resolves to that build, not the newest in the metadata"
      (is (= "88" (resolve "07.20.3-20120809.112124-88"))))
    (testing "the -SNAPSHOT version then resolves to the newest build"
      (is (= "9797" (resolve "07.20.3-SNAPSHOT"))))
    (testing "the pinned build resolves again after the newest one"
      (is (= "88" (resolve "07.20.3-20120809.112124-88"))))))

(deftest unlisted-repository-test
  (let [builds [["20120809.112124-88" "88"] ["20120809.112920-97" "9797"]]
        a (snapshot-repo (fs/file dir "remote-a") "a" builds)
        b (snapshot-repo (fs/file dir "remote-b") "b" [])
        c (snapshot-repo (fs/file dir "remote-c") "c" builds)
        pinned (artifact nil "07.20.3-20120809.112124-88")
        snapshot (artifact nil "07.20.3-SNAPSHOT")
        local-pinned (str (fs/file dir "local5"))
        local-snapshot (str (fs/file dir "local6"))
        tracking (fn [local] (slurp (fs/file local "org/apache/maven/its/dep-mng5324/07.20.3-SNAPSHOT/_remote.repositories")))]
    (is (repo/resolve-file! local-pinned [a] pinned))
    (is (repo/resolve-file! local-snapshot [a] snapshot))
    (testing "a pinned build cached from an unlisted repository is unavailable"
      (is (nil? (repo/resolve-file! local-pinned [b] pinned))))
    (testing "a -SNAPSHOT build cached from an unlisted repository is unavailable"
      (is (nil? (repo/resolve-file! local-snapshot [b] snapshot))))
    (testing "a cached pinned build counts once a listed repository has it"
      (is (= "88" (slurp (repo/resolve-file! local-pinned [c] pinned))))
      (is (str/includes? (tracking local-pinned) ">c=")))
    (testing "a cached -SNAPSHOT build counts once the repository of its metadata has it"
      (is (= "9797" (slurp (repo/resolve-file! local-snapshot [c] snapshot))))
      (is (str/includes? (tracking local-snapshot) ">c=")))))

(deftest build-files-test
  (let [root (fs/file dir "remote-d")
        d (snapshot-repo root "d" [["20120809.112124-88" "88"] ["20120809.112920-97" "9797"]])
        local (str (fs/file dir "local7"))
        version-dir (fs/file local "org/apache/maven/its/dep-mng5324/07.20.3-SNAPSHOT")
        pinned (artifact nil "07.20.3-20120809.112124-88")
        snapshot (artifact nil "07.20.3-SNAPSHOT")
        resolve #(some-> (repo/resolve-file! local [d] %) slurp)]
    (is (= "88" (resolve pinned)))
    (is (= "9797" (resolve snapshot)))
    (testing "each build is kept and tracked under its own name"
      (is (= "88" (slurp (fs/file version-dir "dep-mng5324-07.20.3-20120809.112124-88.jar"))))
      (is (= "9797" (slurp (fs/file version-dir "dep-mng5324-07.20.3-20120809.112920-97.jar"))))
      (is (= #{"dep-mng5324-07.20.3-20120809.112124-88.jar>d=" "dep-mng5324-07.20.3-20120809.112920-97.jar>d="}
             (set (filter #(str/includes? % ">") (str/split-lines (slurp (fs/file version-dir "_remote.repositories"))))))))
    (testing "the -SNAPSHOT file is copied again after another tool overwrote it"
      (spit (fs/file version-dir "dep-mng5324-07.20.3-SNAPSHOT.jar") "88")
      (fs/set-last-modified-time (fs/file version-dir "dep-mng5324-07.20.3-SNAPSHOT.jar") (fs/millis->file-time 1000000))
      (is (= "9797" (resolve snapshot))))
    (testing "both builds resolve from the local repository once the repository is gone"
      (fs/delete-tree root)
      (is (= "88" (resolve pinned)))
      (is (= "9797" (resolve snapshot)))
      (is (= "88" (resolve pinned))))))

(deftest shared-tracking-test
  (testing "a repository tracked for one build does not count for another"
    (let [b (snapshot-repo (fs/file dir "remote-b2") "b" [["20120809.112920-97" "9797"]])
          x (snapshot-repo (fs/file dir "remote-x") "x" [["20120809.112124-88" "88"]])
          local (str (fs/file dir "local8"))]
      (is (= "9797" (slurp (repo/resolve-file! local [b] (artifact nil "07.20.3-SNAPSHOT")))))
      (is (= "88" (slurp (repo/resolve-file! local [x] (artifact nil "07.20.3-20120809.112124-88")))))
      (is (nil? (repo/resolve-file! local [b] (artifact nil "07.20.3-20120809.112124-88")))))))

(let [{:keys [fail error]} (t/run-tests 'snapshot-test)]
  (fs/delete-tree dir)
  (System/exit (if (zero? (+ fail error)) 0 1)))
