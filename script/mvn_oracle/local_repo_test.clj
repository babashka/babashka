#!/usr/bin/env bb
;; babashka.impl.mvn.repo's use of files already in the local repository,
;; against maven-resolver's EnhancedLocalRepositoryManagerTest and the
;; existence check in DefaultArtifactResolver (1.9.27): a file cached from a
;; repository outside the request counts once a requested repository has it.
;; Run: ./bb -cp resources/src/babashka script/mvn_oracle/local_repo_test.clj
(ns local-repo-test
  (:require [babashka.fs :as fs]
            [babashka.impl.mvn.repo :as repo]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [clojure.tools.deps.extensions :as ext]
            [clojure.tools.deps.extensions.maven]
            [clojure.tools.deps.util.session :as session]
            [org.httpkit.server :as server]))

(def ^:private artifact {:group "g" :artifact "a" :version "1" :extension "jar"})

(defn- remote [id url & {:keys [enabled] :or {enabled true}}]
  {:id id :url url
   :releases {:enabled enabled :update :daily :checksum :warn}
   :snapshots {:enabled enabled :update :daily :checksum :warn}})

(defn- file-remote [id dir & opts]
  (fs/create-dirs dir)
  (apply remote id (str (.toURI (fs/file dir))) opts))

(defn- cache!
  "Writes g/a/1/a-1.jar to local with these _remote.repositories lines, no
  such file when lines is nil. Returns the version directory."
  [local lines]
  (let [d (fs/file local "g" "a" "1")]
    (fs/create-dirs d)
    (spit (fs/file d "a-1.jar") "cached")
    (when lines
      (spit (fs/file d "_remote.repositories") (str/join "\n" (cons "#NOTE: This is a Maven Resolver internal implementation file" lines))))
    d))

(defn- publish! [root]
  (let [d (fs/file root "g" "a" "1")]
    (fs/create-dirs d)
    (spit (fs/file d "a-1.jar") "remote")))

(deftest cached-file-test
  (fs/with-temp-dir [dir {}]
    (let [empty-repo (file-remote "local" (fs/file dir "empty"))
          full-root (fs/file dir "full")
          _ (publish! full-root)
          full-repo (file-remote "local" full-root)
          resolve (fn [local repos] (repo/resolve-file! (str local) repos artifact))]
      (testing "testFindUntrackedFile: a file without _remote.repositories is used"
        (let [local (fs/file dir "untracked")]
          (cache! local nil)
          (is (some? (resolve local [empty-repo])))))
      (testing "tracking is per file: an entry for the POM leaves the jar untracked"
        (let [local (fs/file dir "pom-only")]
          (cache! local ["a-1.pom>other="])
          (is (some? (resolve local [empty-repo])))))
      (testing "testFindLocalArtifact: a locally installed file is used"
        (let [local (fs/file dir "installed")]
          (cache! local ["a-1.jar>="])
          (is (some? (resolve local [empty-repo])))))
      (testing "testFindRemoteArtifact: a file from a requested repository is used"
        (let [local (fs/file dir "requested")]
          (cache! local ["a-1.jar>local="])
          (is (some? (resolve local [empty-repo])))))
      (testing "testDoNotFindDifferentContext: a file from another repository is not used"
        (let [local (fs/file dir "other")]
          (cache! local ["a-1.jar>other="])
          (is (nil? (resolve local [empty-repo])))))
      (testing "a file from another repository is used once a requested repository has it"
        (let [local (fs/file dir "verified")
              d (cache! local ["a-1.jar>other="])]
          (is (some? (resolve local [full-repo])))
          (is (= "cached" (slurp (fs/file d "a-1.jar"))))
          (is (str/includes? (slurp (fs/file d "_remote.repositories")) "a-1.jar>local="))
          (is (str/includes? (slurp (fs/file d "_remote.repositories")) "a-1.jar>other="))))
      (testing "a repository with releases disabled is not asked"
        (let [local (fs/file dir "disabled")]
          (cache! local ["a-1.jar>other="])
          (is (nil? (resolve local [(file-remote "local" full-root :enabled false)]))))))))

(deftest existence-check-over-http-test
  (testing "the check is a HEAD request and the cached file stays"
    (fs/with-temp-dir [dir {}]
      (let [root (fs/file dir "remote")
            seen (atom [])
            stop (server/run-server (fn [{:keys [uri request-method]}]
                                      (swap! seen conj [request-method uri])
                                      (let [f (fs/file root (subs uri 1))]
                                        (if (fs/exists? f)
                                          {:status 200 :body (slurp f)}
                                          {:status 404 :body "missing"})))
                                    {:port 0 :legacy-return-value? false})
            url (str "http://localhost:" (server/server-port stop) "/")
            local (fs/file dir "local")]
        (try
          (publish! root)
          (let [d (cache! local ["a-1.jar>other="])]
            (is (some? (repo/resolve-file! (str local) [(remote "local" url)] artifact)))
            (is (= [[:head "/g/a/1/a-1.jar"]] @seen))
            (is (= "cached" (slurp (fs/file d "a-1.jar")))))
          (finally
            (server/server-stop! stop)))))))

(deftest pom-from-other-repository-test
  (testing "an artifact whose POM is cached from another repository resolves without its dependencies"
    (fs/with-temp-dir [dir {}]
      (let [local (fs/file dir "local")
            d (fs/file local "g" "b" "1")]
        (fs/create-dirs d)
        (spit (fs/file d "b-1.pom")
              (str "<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>b</artifactId><version>1</version>"
                   "<dependencies><dependency><groupId>g</groupId><artifactId>c</artifactId><version>1</version></dependency></dependencies></project>"))
        (spit (fs/file d "_remote.repositories") "b-1.pom>other=\n")
        (fs/create-dirs (fs/file dir "empty"))
        (binding [*err* (java.io.StringWriter.)]
          (session/with-session
            (is (= [] (ext/coord-deps 'g/b {:mvn/version "1"} :mvn
                                      {:mvn/repos {"local" {:url (str (.toURI (fs/file dir "empty")))}}
                                       :mvn/local-repo (str local)})))))))))

(let [{:keys [fail error]} (t/run-tests 'local-repo-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
