#!/usr/bin/env bb

(ns bump-version
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(import '[java.lang ProcessBuilder$Redirect])

(defn shell-command
  "Executes shell command. Exits script when the shell-command has a non-zero exit code, propagating it.
  Accepts the following options:
  `:input`: instead of reading from stdin, read from this string.
  `:to-string?`: instead of writing to stdoud, write to a string and
  return it."
  ([args] (shell-command args nil))
  ([args {:keys [:input :to-string?]}]
   (let [args (mapv str args)
         pb (cond-> (-> (ProcessBuilder. ^java.util.List args)
                        (.redirectError ProcessBuilder$Redirect/INHERIT))
              (not to-string?) (.redirectOutput ProcessBuilder$Redirect/INHERIT)
              (not input) (.redirectInput ProcessBuilder$Redirect/INHERIT))
         proc (.start pb)]
     (when input
       (with-open [w (io/writer (.getOutputStream proc))]
         (binding [*out* w]
           (print input)
           (flush))))
     (let [string-out
           (when to-string?
             (let [sw (java.io.StringWriter.)]
               (with-open [w (io/reader (.getInputStream proc))]
                 (io/copy w sw))
               (str sw)))
           exit-code (.waitFor proc)]
       (when-not (zero? exit-code)
         (System/exit exit-code))
       string-out))))

(def version-file (io/file "resources" "BABASHKA_VERSION"))
(def released-version-file (io/file "resources" "BABASHKA_RELEASED_VERSION"))

(case (first *command-line-args*)
  "release" (let [version-string (str/trim (slurp version-file))
                  [major minor patch] (str/split version-string #"\.")
                  patch (str/replace patch "-SNAPSHOT" "")
                  new-version (str/join "." [major minor patch])]
              (spit version-file new-version)
              (shell-command ["git" "commit" "-a" "-m" (str "v" new-version)])
              (shell-command ["git" "diff" "HEAD^" "HEAD"]))
  "post-release" (do
                   (io/copy version-file released-version-file)
                   (let [version-string (str/trim (slurp version-file))
                         [major minor patch] (str/split version-string #"\.")
                         patch (Integer. patch)
                         patch (str (inc patch) "-SNAPSHOT")
                         new-version (str/join "." [major minor patch])]
                     (spit version-file new-version)
                     (shell-command ["git" "commit" "-a" "-m" "Version bump"])
                     (shell-command ["git" "diff" "HEAD^" "HEAD"])))
  (println "Expected: release | post-release."))
