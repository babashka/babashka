#!/usr/bin/env bb

;; NOTE
;; 
;; For more information on the current scene on support for
;; particular GraalVM versions, look here: https://www.graalvm.org/downloads/
;; 
;; There are 4 CE(Community Editions) being supported by GraalVM
;; GraalVM Community Edition 20.1.0 based on OpenJDK 8u252
;; GraalVM Community Edition 20.1.0 based on OpenJDK 11.0.7
;; GraalVM Community Edition 19.3.2 based on OpenJDK 8u252
;; GraalVM Community Edition 19.3.2 based on OpenJDK 11.0.7
;; 
;; Currently we use GraalVM java8-19.3.2

(ns bump-graal-version
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]))

(defn display-help []
  (println (->> [""
                 "This is a script that should be run when you'd"
                 "you'd like to bump the GraalVM version for bb."
                 ""
                 "Use it by providing one command line argument"
                 "i.e the version you'd want to upgrade it to"
                 ""
                 "./bump_graal_version.clj 19.3.2 (the new version)"
                 ""]
                (str/join \newline))))

(def files-to-edit
  [".github/workflows/build.yml"
   ".circleci/config.yml"
   "appveyor.yml"])

(def current-version "19.3.2")

(def valid-bumps ["19.3.2", "20.1.0"])

(defn is-valid-bump?
  [version]
  (some #(= % version) valid-bumps))

(defn run-shell
  [sed-instr file]
  ;; the empty string is added after -i so that
  ;; a backup file isn't generated.
  ;; this is issue specific to macOS
  (shell/sh "sed" "-i" "" "-e" sed-instr file))

(defn bump-version
  [new-version]
  (let [sed-instr (str "s/" current-version "/" new-version "/g")
        runfn (partial run-shell sed-instr)]
    (doseq [file files-to-edit]
      (let [exec-res (runfn file)
            exit-res (:exit exec-res)
            err-res (:err exec-res)]
        (if (zero? exit-res)
          (println "Updated file: " file)
          (println "There was an error updating: " file "\n" err-res))))))

(defn exec-script []
  (let [arg *command-line-args*
        arg-count (count arg)]
    (if (not= 1 arg-count)
      (do
        (println "Incorrect number of args passed!")
        (display-help))
      (let [new-version (first arg)]
        (if (is-valid-bump? new-version)
          (bump-version new-version)
          (println "Please be sure to check if it is a supported version: " new-version))))))

(exec-script)
