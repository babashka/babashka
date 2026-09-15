#!/usr/bin/env bb
;; tools.build's install task in bb against what Resolver's installer writes
;; (tools.build 0.10.14 on the JVM): the files, their tracking, and the
;; metadata a -SNAPSHOT gets, then resolving the installs back.
;; Run: ./bb -cp resources/src/babashka script/mvn_oracle/install_test.clj
(ns install-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [clojure.tools.build.api :as b]
            [clojure.tools.deps.extensions :as ext]
            [clojure.tools.deps.extensions.maven]
            [clojure.tools.deps.util.session :as session]))

(def dir (fs/create-temp-dir))
(def project (fs/file dir "project"))
(def local-repo (str (fs/file dir "m2")))

(defn- install! [version classifier]
  (b/set-project-root! (str project))
  (let [basis (b/create-basis {:project "deps.edn" :root nil :user nil :extra {:mvn/local-repo local-repo}})
        suffix (str version (when classifier (str "-" classifier)))
        class-dir (str "target/classes-" suffix)
        jar-file (str "target/lib-" suffix ".jar")]
    (b/copy-dir {:src-dirs ["src"] :target-dir class-dir})
    (b/write-pom {:class-dir class-dir :lib 'probe/lib :version version :basis basis :src-dirs ["src"]})
    (b/jar {:class-dir class-dir :jar-file jar-file})
    (b/install {:basis basis :lib 'probe/lib :version version :classifier classifier :jar-file jar-file :class-dir class-dir})))

(defn- tracking [version]
  (set (remove #(str/starts-with? % "#") (str/split-lines (slurp (fs/file local-repo "probe" "lib" version "_remote.repositories"))))))

(defn- normalized [f]
  (str/replace (slurp f) #"\d{14}" "STAMP"))

(defn- resolves? [lib version]
  (session/with-session
    (= [(str (fs/file local-repo "probe" "lib" version (str "lib-" version (when-let [c (some-> (name lib) (str/split #"\$") second)] (str "-" c)) ".jar")))]
       (ext/coord-paths lib {:mvn/version version} :mvn {:mvn/repos {"central" nil "clojars" nil} :mvn/local-repo local-repo}))))

(deftest install-test
  (fs/create-dirs (fs/file project "src" "probe"))
  (spit (fs/file project "src" "probe" "core.clj") "(ns probe.core)")
  (spit (fs/file project "deps.edn") "{:paths [\"src\"]}")
  (testing "a release: jar, POM, both marked installed, the version in the artifact's metadata"
    (install! "1.0.0" nil)
    (is (= #{"lib-1.0.0.jar>=" "lib-1.0.0.pom>="} (tracking "1.0.0")))
    (is (str/includes? (slurp (fs/file local-repo "probe" "lib" "maven-metadata-local.xml")) "<version>1.0.0</version>"))
    (is (not (fs/exists? (fs/file local-repo "probe" "lib" "1.0.0" "maven-metadata-local.xml"))))
    (is (resolves? 'probe/lib "1.0.0")))
  (testing "a snapshot: the version directory's metadata names a local copy and each installed file"
    (install! "2.0.0-SNAPSHOT" nil)
    (is (= #{"lib-2.0.0-SNAPSHOT.jar>=" "lib-2.0.0-SNAPSHOT.pom>="} (tracking "2.0.0-SNAPSHOT")))
    (is (= (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                "<metadata modelVersion=\"1.1.0\">\n"
                "  <groupId>probe</groupId>\n"
                "  <artifactId>lib</artifactId>\n"
                "  <versioning>\n"
                "    <lastUpdated>STAMP</lastUpdated>\n"
                "    <snapshot>\n"
                "      <localCopy>true</localCopy>\n"
                "    </snapshot>\n"
                "    <snapshotVersions>\n"
                "      <snapshotVersion>\n"
                "        <extension>jar</extension>\n"
                "        <value>2.0.0-SNAPSHOT</value>\n"
                "        <updated>STAMP</updated>\n"
                "      </snapshotVersion>\n"
                "      <snapshotVersion>\n"
                "        <extension>pom</extension>\n"
                "        <value>2.0.0-SNAPSHOT</value>\n"
                "        <updated>STAMP</updated>\n"
                "      </snapshotVersion>\n"
                "    </snapshotVersions>\n"
                "  </versioning>\n"
                "  <version>2.0.0-SNAPSHOT</version>\n"
                "</metadata>\n")
           (normalized (fs/file local-repo "probe" "lib" "2.0.0-SNAPSHOT" "maven-metadata-local.xml"))))
    (is (resolves? 'probe/lib "2.0.0-SNAPSHOT")))
  (testing "a classifier install of the same snapshot adds its files first and keeps the others"
    (install! "2.0.0-SNAPSHOT" "docs")
    (let [text (normalized (fs/file local-repo "probe" "lib" "2.0.0-SNAPSHOT" "maven-metadata-local.xml"))]
      (is (= ["docs" "docs"] (map second (re-seq #"<classifier>([^<]+)</classifier>" text))))
      (is (= 4 (count (re-seq #"<snapshotVersion>" text))))
      (is (< (str/index-of text "<classifier>docs</classifier>") (str/last-index-of text "<extension>jar</extension>"))))
    (is (resolves? 'probe/lib$docs "2.0.0-SNAPSHOT"))))

(let [{:keys [fail error]} (t/run-tests 'install-test)]
  (fs/delete-tree dir)
  (System/exit (if (zero? (+ fail error)) 0 1)))
