#!/usr/bin/env bb
;; Adds a library to bb-tested-libs.edn to be tested given a library version and
;; git repository. Optionally takes a --test to then test the added library.

(ns add-libtest
  (:require [babashka.deps :as deps]
            [babashka.fs :as fs]
            [babashka.tasks :refer [shell]]
            [clojure.string :as str]
            [clojure.edn :as edn]))

(deps/add-deps '{:deps {org.clojure/tools.gitlibs {:mvn/version "2.4.172"}
                        borkdude/rewrite-edn {:mvn/version "0.1.0"}}})

(require '[clojure.tools.gitlibs :as gl])
(require '[borkdude.rewrite-edn :as r])

(defn- add-lib-to-deps
  [lib-name lib-coordinate]
  (let [nodes (-> "deps.edn" slurp r/parse-string)]
    (spit "deps.edn"
         (str (r/assoc-in nodes
                          [:aliases :lib-tests :extra-deps (symbol lib-name)]
                          lib-coordinate)))))

(defn- copy-tests
  [git-url lib-name]
  (let [lib-dir (or (gl/procure git-url lib-name "master")
                    (gl/procure git-url lib-name "main"))
        test-dir (some #(when (fs/exists? (fs/file lib-dir %))
                          (str (fs/file lib-dir %)))
                       ;; Search common test dirs
                       ["test"
                        ;; official clojure repos like https://github.com/clojure/tools.gitlibs
                        "src/test/clojure"])]
    (shell "cp -R" (str test-dir fs/file-separator) "test-resources/lib_tests/")
    {:lib-dir lib-dir
     :test-dir test-dir}))

(defn- add-lib-to-tested-libs
  [lib-name git-url {:keys [lib-dir test-dir]}]
  (let [git-sha (fs/file-name lib-dir)
        ; (str (fs/relativize lib-dir test-dir))
        relative-test-files (map #(str (fs/relativize test-dir %))
                                 (fs/glob test-dir "**/*.{clj,cljc}"))
        _ (when (empty? relative-test-files)
            (throw (ex-info "No test files found" {:test-dir test-dir})))
        namespaces (map #(-> %
                             (str/replace fs/file-separator ".")
                             (str/replace "_" "-")
                             (str/replace-first #"\.clj(c?)$" "")
                             symbol)
                        relative-test-files)
        lib {:git-sha git-sha
             :git-url git-url
             :test-namespaces namespaces}
        nodes (-> "test-resources/lib_tests/bb-tested-libs.edn" slurp r/parse-string)]
    (spit "test-resources/lib_tests/bb-tested-libs.edn"
         (str (r/assoc-in nodes
                          [(symbol lib-name)]
                          lib)))
    namespaces))

(defn- run-command
  [args]
  (let [[deps-string git-url test-option] args
        deps-map (edn/read-string deps-string)
        _ (when (not= 1 (count deps-map))
            (throw (ex-info "Deps map must have one key" {})))
        lib-name (ffirst deps-map)
        lib-coordinate (deps-map lib-name)
        _ (add-lib-to-deps lib-name lib-coordinate)
        dirs (copy-tests git-url lib-name)
        namespaces (add-lib-to-tested-libs lib-name git-url dirs)]
    (println "Added lib" lib-name "which tests the following namespaces:" namespaces)
    (when (= "--test" test-option)
      (apply shell "script/lib_tests/run_all_libtests" namespaces))))

(defn main
  [args]
  (if (< (count args) 2)
    (println "Usage: bb add-libtest DEPS-MAP GIT-URL [--test]")
    (run-command args)))

(when (= *file* (System/getProperty "babashka.file"))
  (main *command-line-args*))
