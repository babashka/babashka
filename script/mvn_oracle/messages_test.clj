#!/usr/bin/env bb
;; What a user reads when resolution fails, in the CLI's words. The
;; unknown-artifact case asks central and clojars; the rest stay local.
;; Run: ./bb -cp resources/src/babashka script/mvn_oracle/messages_test.clj
(ns messages-test
  (:require [babashka.deps :as deps]
            [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]))

(defn- failure
  "The message add-deps throws for deps, or nil."
  [deps]
  (try (deps/add-deps deps {:force true :extra-env {"BABASHKA_DEPS_RESOLVER" "native"}}) nil
       (catch Exception e (ex-message e))))

(defn- bad-repo!
  "A file: repository with one artifact whose POM checksum is wrong and whose
  jar has none. Returns its URL."
  [dir]
  (let [d (fs/file dir "bad" "lib" "1.0.0")]
    (fs/create-dirs d)
    (spit (fs/file d "lib-1.0.0.pom")
          "<project><modelVersion>4.0.0</modelVersion><groupId>bad</groupId><artifactId>lib</artifactId><version>1.0.0</version></project>")
    (spit (fs/file d "lib-1.0.0.pom.sha1") "0000000000000000000000000000000000000000\n")
    (spit (fs/file d "lib-1.0.0.jar") "PK")
    (str "file://" dir "/")))

(deftest not-found-test
  (is (= "Could not find artifact nope:nope:pom:1.0.0 in central (https://repo1.maven.org/maven2/), clojars (https://repo.clojars.org/)"
         (failure '{:deps {nope/nope {:mvn/version "1.0.0"}}}))))

(deftest unreachable-test
  (is (= "Could not transfer nope/nope/1.0.0/nope-1.0.0.pom from dead (https://nonexistent.invalid/maven2/): nonexistent.invalid"
         (failure '{:deps {nope/nope {:mvn/version "1.0.0"}}
                    :mvn/repos {"dead" {:url "https://nonexistent.invalid/maven2/"}}}))))

(deftest s3-test
  (is (= "S3 repository private (s3://bucket/releases/) requires the JVM resolver. Set BABASHKA_DEPS_RESOLVER=jvm."
         (failure '{:deps {nope/nope {:mvn/version "1.0.0"}}
                    :mvn/repos {"private" {:url "s3://bucket/releases/"}}})))
  (testing "a mirror in settings.xml can stand in for the bucket"
    (fs/with-temp-dir [home {}]
      (let [m2 (fs/file home ".m2")
            real-home (System/getProperty "user.home")]
        (fs/create-dirs m2)
        (spit (fs/file m2 "settings.xml")
              "<settings><mirrors><mirror><id>bucket-mirror</id><url>https://repo.clojars.org/</url><mirrorOf>private</mirrorOf></mirror></mirrors></settings>")
        (System/setProperty "user.home" (str home))
        (try
          (is (= "Could not find artifact nope:nope:pom:1.0.0 in central (https://repo1.maven.org/maven2/), clojars (https://repo.clojars.org/), bucket-mirror (https://repo.clojars.org/)"
                 (failure '{:deps {nope/nope {:mvn/version "1.0.0"}}
                            :mvn/repos {"private" {:url "s3://bucket/releases/"}}})))
          (finally (System/setProperty "user.home" real-home)))))))

(deftest bad-coordinate-test
  (is (= "No :mvn/version specified for medley/medley"
         (failure '{:deps {medley/medley {:mvn/version nil}}})))
  (testing "an empty coordinate is tools.deps' own error"
    (is (= "Coord of unknown type: {}" (failure '{:deps {medley/medley {}}}))))
  (is (= "Invalid :mvn/version for medley/medley: 1"
         (failure '{:deps {medley/medley {:mvn/version 1}}}))))

(deftest checksum-test
  (fs/with-temp-dir [dir {}]
    (let [url (bad-repo! (fs/file dir "repo"))
          local (str (fs/file dir "local-repo"))]
      (testing ":fail stops at the mismatch"
        (is (str/starts-with?
             (failure {:deps '{bad/lib {:mvn/version "1.0.0"}}
                       :mvn/local-repo local
                       :mvn/repos {"local" {:url url :releases {:checksum :fail}}}})
             "Checksum validation failed for bad/lib/1.0.0/lib-1.0.0.pom, expected 0000000000000000000000000000000000000000 but is ")))
      (testing ":warn says so for the mismatch and for the missing checksum, and goes on"
        (let [err (with-out-str
                    (binding [*err* *out*]
                      (is (nil? (failure {:deps '{bad/lib {:mvn/version "1.0.0"}}
                                          :mvn/local-repo local
                                          :mvn/repos {"local" {:url url}}})))))]
          (is (str/includes? err "Checksum validation failed for bad/lib/1.0.0/lib-1.0.0.pom, expected 0000000000000000000000000000000000000000 but is "))
          (is (str/includes? err "Checksum validation failed for bad/lib/1.0.0/lib-1.0.0.jar, no checksums available")))))))

(let [{:keys [fail error]} (t/run-tests 'messages-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
