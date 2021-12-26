(ns babashka.release-artifact
  (:require [borkdude.gh-release-artifact :as ghr]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]))

(defn current-branch []
  (or (System/getenv "APPVEYOR_PULL_REQUEST_HEAD_REPO_BRANCH")
      (System/getenv "APPVEYOR_REPO_BRANCH")
      (System/getenv "CIRCLE_BRANCH")
      (-> (sh "git" "rev-parse" "--abbrev-ref" "HEAD")
          :out
          str/trim)))

(defn release [& args]
  (let [current-version (-> (slurp "resources/BABASHKA_VERSION")
                            str/trim)
        ght (System/getenv "GITHUB_TOKEN")
        file (first args)
        branch (current-branch)]
    (if (and ght (contains? #{"master" "main"} branch))
      (do (assert file "File name must be provided")
          (ghr/overwrite-asset {:org "babashka"
                                :repo "babashka"
                                :file file
                                :tag (str "v" current-version)
                                :draft true})
          (ghr/overwrite-asset {:org "babashka"
                                :repo "babashka-dev-builds"
                                :file file
                                :tag (str "v" current-version)
                                :draft false}))
      (println "Skipping release artifact (no GITHUB_TOKEN or not on main branch)"))
    nil))
