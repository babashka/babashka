#!/usr/bin/env bb
;; Adds a library to bb-tested-libs.edn to be tested given a library version and
;; git repository. Optionally takes a --test to then test the added library.

(ns add-libtest
  (:require [babashka.deps :as deps]
            [babashka.fs :as fs]
            [babashka.tasks :refer [shell]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.tools.cli :as cli]
            [clojure.edn :as edn]))

(deps/add-deps '{:deps {org.clojure/tools.gitlibs {:mvn/version "2.4.172"}
                        borkdude/rewrite-edn {:mvn/version "0.1.0"}}})

(require '[clojure.tools.gitlibs :as gl])
(require '[borkdude.rewrite-edn :as r])

;; CLI Utils
;; =========
(defn- error
  "Print error message(s) and exit"
  [& msgs]
  (apply println "Error:" msgs)
  (System/exit 1))

(defn- print-summary
  "Print help summary given args and opts strings"
  [args-string options-summary]
  (println (format "Usage: %s [OPTIONS]%s\nOptions:\n%s"
                   (.getName (io/file *file*))
                   args-string
                   options-summary)))

(defn- run-command
  "Processes a command's functionality given a cli options definition, arguments
  and primary command fn. This handles option parsing, handles any errors with
  parsing and then passes parsed input to command fn"
  [command-fn args cli-opts & parse-opts-options]
  (let [{:keys [errors] :as parsed-input}
        (apply cli/parse-opts args cli-opts parse-opts-options)]
    (if (seq errors)
      (do
        (error (str/join "\n" (into ["Options failed to parse:"] errors)))
        (System/exit 1))
      (command-fn parsed-input))))

;; Add libtest
;; ===========
(defn- add-lib-to-deps
  [lib-name lib-coordinate]
  (let [nodes (-> "deps.edn" slurp r/parse-string)]
    (spit "deps.edn"
         (str (r/assoc-in nodes
                          [:aliases :lib-tests :extra-deps (symbol lib-name)]
                          lib-coordinate)))))

(defn- copy-tests
  [git-url lib-name {:keys [directory branch]}]
  (let [lib-dir (if branch
                  (gl/procure git-url lib-name branch)
                  (or (gl/procure git-url lib-name "master")
                      (gl/procure git-url lib-name "main")))
        lib-root-dir (if directory
                       (fs/file lib-dir directory) lib-dir)
        test-dir (some #(when (fs/exists? (fs/file lib-root-dir %))
                          (str (fs/file lib-root-dir %)))
                       ;; Search common test dirs
                       ["test"
                        ;; official clojure repos like https://github.com/clojure/tools.gitlibs
                        "src/test/clojure"])]
    (when-not test-dir
      (error "No test dir found"))
    (shell "cp -R" (str test-dir fs/file-separator) "test-resources/lib_tests/")
    {:lib-dir lib-dir
     :test-dir test-dir}))

(defn- add-lib-to-tested-libs
  [lib-name git-url {:keys [lib-dir test-dir]} options]
  (let [git-sha (fs/file-name lib-dir)
        relative-test-files (map #(str (fs/relativize test-dir %))
                                 (fs/glob test-dir "**/*.{clj,cljc}"))
        _ (when (empty? relative-test-files)
            (error "No test files found"))
        namespaces (map #(-> %
                             (str/replace fs/file-separator ".")
                             (str/replace "_" "-")
                             (str/replace-first #"\.clj(c?)$" "")
                             symbol)
                        relative-test-files)
        lib (merge
             {:git-sha git-sha
              :git-url git-url
              :test-namespaces namespaces}
             ;; Options needed to update libs
             (select-keys options [:branch :directory]))
        nodes (-> "test-resources/lib_tests/bb-tested-libs.edn" slurp r/parse-string)]
    (spit "test-resources/lib_tests/bb-tested-libs.edn"
         (str (r/assoc-in nodes
                          [(symbol lib-name)]
                          lib)))
    namespaces))

(defn- add-libtest*
  [args options]
  (let [[deps-string git-url] args
        deps-map (edn/read-string deps-string)
        _ (when (not= 1 (count deps-map))
            (error "Deps map must have one key"))
        lib-name (ffirst deps-map)
        lib-coordinate (deps-map lib-name)
        _ (add-lib-to-deps lib-name lib-coordinate)
        dirs (copy-tests git-url lib-name options)
        namespaces (add-lib-to-tested-libs lib-name git-url dirs options)]
    (println "Added lib" lib-name "which tests the following namespaces:" namespaces)
    (when (:test options)
      (apply shell "script/lib_tests/run_all_libtests" namespaces))))

(defn add-libtest
  [{:keys [arguments options summary]}]
  (if (or (< (count arguments) 2) (:help options))
    (print-summary "DEPS-MAP GIT-URL " summary)
    (add-libtest* arguments options)))

(def cli-options
  [["-h" "--help"]
   ["-t" "--test" "Run tests"]
   ;; https://github.com/weavejester/environ/tree/master/environ used this option
   ["-d" "--directory DIRECTORY" "Directory where library is located"]
   ;; https://github.com/reifyhealth/specmonstah used this option
   ["-b" "--branch BRANCH" "Default branch for git url"]])

(when (= *file* (System/getProperty "babashka.file"))
  (run-command add-libtest *command-line-args* cli-options))
