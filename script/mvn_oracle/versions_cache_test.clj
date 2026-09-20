#!/usr/bin/env bb
;; find-versions in one session returns the versions of the given repositories.
;; Run: ./bb -cp resources/src/babashka script/mvn_oracle/versions_cache_test.clj
(ns versions-cache-test
  (:require [babashka.fs :as fs]
            [clojure.test :as t :refer [deftest is testing]]
            [clojure.tools.deps.extensions :as ext]
            [clojure.tools.deps.extensions.maven]
            [clojure.tools.deps.util.session :as session]))

(defn- publish-versions! [root versions]
  (let [d (fs/file root "acme" "lib")]
    (fs/create-dirs d)
    (spit (fs/file d "maven-metadata.xml")
          (str "<metadata><groupId>acme</groupId><artifactId>lib</artifactId><versioning><versions>"
               (apply str (map #(str "<version>" % "</version>") versions))
               "</versions></versioning></metadata>"))))

(deftest versions-per-repository-test
  (fs/with-temp-dir [dir {}]
    (let [config (fn [id] {:mvn/repos {id {:url (str (.toURI (fs/file dir id)))}}
                           :mvn/local-repo (str (fs/file dir "local"))})
          versions (fn [id] (mapv :mvn/version (ext/find-versions 'acme/lib nil :mvn (config id))))]
      (publish-versions! (fs/file dir "a") ["1.0.0"])
      (publish-versions! (fs/file dir "b") ["1.0.0" "2.0.0"])
      (binding [*err* (java.io.StringWriter.)]
        (session/with-session
          (testing "find-versions returns the versions of each repository in one session"
            (is (= ["1.0.0"] (versions "a")))
            (is (= ["1.0.0" "2.0.0"] (versions "b"))))

          (testing "a repeated lookup returns the versions of its repository"
            (is (= ["1.0.0"] (versions "a")))))))))

(deftest first-lookup-test
  (fs/with-temp-dir [dir {}]
    (let [config {:mvn/repos {"a" {:url (str (.toURI (fs/file dir "a")))}}
                  :mvn/local-repo (str (fs/file dir "local"))}
          libs (map #(symbol "acme" (str "lib" %)) (range 64))]
      (testing "find-versions returns for 64 lib names as the first lookup of a session"
        (binding [*err* (java.io.StringWriter.)]
          (is (= [] (for [lib libs
                          :let [message (try (session/with-session (ext/find-versions lib nil :mvn config))
                                             nil
                                             (catch Exception e (ex-message e)))]
                          :when message]
                      [lib message]))))))))

(let [{:keys [fail error]} (t/run-tests 'versions-cache-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
