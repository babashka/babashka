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
;; Currently we use GraalVM java11-20.1.0

(ns bump-graal-version
  (:require [clojure.string :as str]
            [clojure.tools.cli :as cli]))

(defn display-help []
  (println (->> [""
                 "This is a script that should be run when you'd"
                 "you'd like to bump the GraalVM/java version for bb."
                 ""
                 "The following options are available:"
                 "(-g, --graal)  - to specify the GraalVM version"
                 "(-j, --java)   - to specify the java version"
                 ""
                 "Use it by providing one or 2 command line arguments"
                 "i.e the version you'd want to upgrade it to and/or the java version"
                 ""
                 "./bump_graal_version.clj -g 19.3.2 (the new version)"
                 "or"
                 "./bump_graal_version.clj -g 19.3.2 --java java11"
                 "(for GraalVM java11-19.3.2)"
                 ""]
                (str/join \newline))))

(def files-to-edit
  ["Dockerfile"
   "doc/dev.md"
   ".github/workflows/build.yml"
   ".circleci/config.yml"
   "appveyor.yml"])

;; We might have to keep changing these from
;; time to time whenever the version is bumped
;;
;; OR
;;
;; We could have them as environment variables
(def current-graal-version "20.1.0")
(def current-java-version "java11")
(def valid-graal-bumps ["19.3.2" "20.1.0"])
(def valid-java-bumps ["java8" "java11"])

(def cl-options
  [["-g" "--graal VERSION" "graal version"]
   ["-j" "--java VERSION" "java version"]
   ["-h" "--help"]])

(def cl-args
  (:options (cli/parse-opts *command-line-args* cl-options)))

(defn is-valid-bump?
  [version valid-bumps]
  (some #(= % version) valid-bumps))

(defn replace-current
  [file current new]
  (let [file-contents (slurp file)]
    (str/replace file-contents current new)))

(defn bump-current
  [current new]
  (doseq [file files-to-edit]
    (let [exec-res (replace-current file current new)]
      (try (spit file exec-res)
           (catch Exception e (str "There was an error: " (.getMessage e)))
           (finally
             (println "Done with : " file))))))

(defn show-error
  [err-version]
  (println "This is not a valid version: " err-version))

(defn exec-script
  [args]
  (when (empty? args)
    (display-help))
  (let [new-graal-version (:graal args)
        new-java-version (:java args)]
    (when (not (nil? new-graal-version))
      (if (is-valid-bump? new-graal-version valid-graal-bumps)
        (do
          (println "Performing Graal bump...")
          (bump-current current-graal-version new-graal-version))
        (show-error new-graal-version)))
    (when (not (nil? new-java-version))
      (if (is-valid-bump? new-java-version valid-java-bumps)
        (do
          (println "Performing Java bump...")
          (bump-current current-java-version new-java-version))
        (show-error new-java-version)))))

(exec-script cl-args)
