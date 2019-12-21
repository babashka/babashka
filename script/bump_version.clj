#!/usr/bin/env bb

(ns bump-version
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def version-file (io/file "resources" "BABASHKA_VERSION"))
(def released-version-file (io/file "resources" "BABASHKA_RELEASED_VERSION"))

(case (first *command-line-args*)
  "release" (let [version-string (str/trim (slurp version-file))
                  [major minor patch] (str/split version-string #"\.")
                  patch (str/replace patch "-SNAPSHOT" "")]
              (spit version-file (str/join "." [major minor patch])))
  "post-release" (do
                   (io/copy version-file released-version-file)
                   (let [version-string (str/trim (slurp version-file))
                         [major minor patch] (str/split version-string #"\.")
                         patch (Integer. patch)
                         patch (str (inc patch) "-SNAPSHOT")]
                     (spit version-file (str/join "." [major minor patch]))))
  (println "Expected: release | post-release."))
