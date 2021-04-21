(ns babashka.release-artifact
  (:require [borkdude.gh-release-artifact :as ghr]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]))

(defn current-branch []
  (-> (sh "git" "rev-parse" "--abbrev-ref" "HEAD")
      :out
      str/trim))

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
                                :tag (str "v" current-version)}))
      (println "Skipping release artifact (no GITHUB_TOKEN or not on main branch)"))
    nil))
