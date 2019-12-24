#!/usr/bin/env bb

(ns bump-version
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.java.shell :refer [sh]]))

(def version-file (io/file "resources" "BABASHKA_VERSION"))
(def released-version-file (io/file "resources" "BABASHKA_RELEASED_VERSION"))

(case (first *command-line-args*)
  "release" (let [version-string (str/trim (slurp version-file))
                  [major minor patch] (str/split version-string #"\.")
                  patch (str/replace patch "-SNAPSHOT" "")
                  new-version (str/join "." [major minor patch])]
              (spit version-file new-version)
              (sh "git" "commit" "-a" "-m" (str "v" new-version))
              (println (:out (sh "git" "diff" "HEAD^" "HEAD"))))
  "post-release" (do
                   (io/copy version-file released-version-file)
                   (let [version-string (str/trim (slurp version-file))
                         [major minor patch] (str/split version-string #"\.")
                         patch (Integer. patch)
                         patch (str (inc patch) "-SNAPSHOT")
                         new-version (str/join "." [major minor patch])]
                     (spit version-file new-version)
                     (sh "git" "commit" "-a" "-m" "Version bump")))
  (println "Expected: release | post-release."))
