(ns babashka.release-artifact
  (:require [borkdude.gh-release-artifact :as ghr]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str])
  (:import [java.io File]))

(defn current-branch []
  (or (System/getenv "APPVEYOR_PULL_REQUEST_HEAD_REPO_BRANCH")
      (System/getenv "APPVEYOR_REPO_BRANCH")
      (System/getenv "CIRCLE_BRANCH")
      (System/getenv "GITHUB_REF_NAME")
      (System/getenv "CIRRUS_BRANCH")
      (-> (sh "git" "rev-parse" "--abbrev-ref" "HEAD")
          :out
        str/trim)))

(defn pull-req-indicator []
  (some #(System/getenv %)
    ["APPVEYOR_PULL_REQUEST_NUMBER"
     "CIRCLE_PR_NUMBER"
     "GITHUB_HEAD_REF"
     "CIRRUS_PR"]))

(defn release [& args]
  (let [ght (System/getenv "GITHUB_TOKEN")
        _ (println "Github token found")
        file (first args)
        _ (println "File" file)
        branch (current-branch)
        _ (println "On branch:" branch)
        current-version
        (-> (slurp "resources/BABASHKA_VERSION")
          str/trim)]
    (if (and ght (contains? #{"master" "main"} branch))
      (do (assert file "File name must be provided")
          (println "On main branch. Publishing asset.")
          (ghr/overwrite-asset {:org "babashka"
                                :repo "babashka"
                                :file file
                                :tag (str "v" current-version)
                                :draft true
                                :overwrite (str/ends-with? current-version "SNAPSHOT")
                                :sha256 true})
          (ghr/overwrite-asset {:org "babashka"
                                :repo "babashka-dev-builds"
                                :file file
                                :tag (str "v" current-version)
                                ;; do not set, because we are posting to another repo
                                :target-commitish false
                                :draft false
                                :prerelease true
                                :overwrite (str/ends-with? current-version "SNAPSHOT")
                                :sha256 true}))
      (println "Skipping release artifact (no GITHUB_TOKEN or not on main branch)"))
    nil))

(defn bb-tests-cmd []
  (let [win? (-> (System/getProperty "os.name")
               str/lower-case
               (str/includes? "windows"))
        skip-flaky-tests (and (contains? #{"main" "master"} (current-branch)) (not (pull-req-indicator)))
        skip-test-metas (vector (if win? :skip-windows :windows-only))
        skip-test-metas (if skip-flaky-tests (conj skip-test-metas :flaky) skip-test-metas)
        script-path (str "script" File/separator "test")]
    [script-path ":excludes" (str skip-test-metas)]))
