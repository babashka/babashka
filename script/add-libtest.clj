#!/usr/bin/env bb
;; Adds a library to bb-tested-libs.edn and libraries.csv and optionally run its
;; tests. There are two modes to this script - automatic (default) and manual.
;; The script defaults to automatically copying tests as this normally works.
;; There are several options to specify where the library is including
;; --git-url, --dir and --test-directories. See --help for more. In manual mode,
;; tests are manually added outside of the script and the script is run to add
;; the library to library lists.

(ns add-libtest
  (:require [babashka.deps :as deps]
            [babashka.fs :as fs]
            [babashka.tasks :refer [shell]]
            [org.httpkit.client :as http]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.tools.cli :as cli]
            [clojure.edn :as edn]))

(deps/add-deps '{:deps {org.clojure/tools.gitlibs {:mvn/version "2.4.172"}
                        borkdude/rewrite-edn {:mvn/version "0.1.0"}
                        org.clojure/data.csv {:mvn/version "1.0.0"}}})

(require '[clojure.tools.gitlibs :as gl])
(require '[borkdude.rewrite-edn :as r])
(require '[clojure.data.csv :as csv])

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
                          [:aliases :lib-tests :extra-deps lib-name]
                          lib-coordinate)))))

(defn- default-test-dir
  [lib-root-dir]
  (some #(when (fs/exists? (fs/file lib-root-dir %))
           (str (fs/file lib-root-dir %)))
        ;; Most common test dir
        ["test"
         ;; official clojure repos like https://github.com/clojure/tools.gitlibs
         "src/test/clojure"]))

(defn- copy-tests
  [git-url lib-name {:keys [directory branch test-directories]}]
  (let [lib-dir (if branch
                  (gl/procure git-url lib-name branch)
                  (or (gl/procure git-url lib-name "master")
                      (gl/procure git-url lib-name "main")
                      (throw (ex-info "Unable to clone git-url" {}))))
        _ (println "Git clone is at" lib-dir)
        lib-root-dir (if directory (fs/file lib-dir directory) lib-dir)
        test-dirs (if test-directories
                    (map #(when (fs/exists? (fs/file lib-root-dir %))
                            (str (fs/file lib-root-dir %)))
                         test-directories)
                    (some-> (default-test-dir lib-root-dir) vector))]
    (when (empty? test-dirs)
      (error "No test directories found"))
    (doseq [test-dir test-dirs]
      (shell "cp -R" (str test-dir fs/file-separator) "test-resources/lib_tests/"))
    {:lib-dir lib-dir
     :test-dirs test-dirs}))

(defn- default-test-namespaces
  [test-dir]
  (let [relative-test-files (map #(str (fs/relativize test-dir %))
                                 (fs/glob test-dir "**/*.{clj,cljc}"))]
    (when (empty? relative-test-files)
      (error (str "No test files found in " test-dir)))
    (map #(-> %
              (str/replace fs/file-separator ".")
              (str/replace "_" "-")
              (str/replace-first #"\.clj(c?)$" "")
              symbol)
         relative-test-files)))

(defn- add-lib-to-tested-libs
  [lib-name git-url {:keys [lib-dir test-dirs]} options]
  (let [namespaces (or (get-in options [:manually-added :test-namespaces])
                       (mapcat default-test-namespaces test-dirs))
        default-lib (merge
                     {:git-url git-url
                      :test-namespaces namespaces}
                     ;; Options needed to update libs
                     (select-keys options [:branch :directory :test-directories]))
        lib (if (:manually-added options)
              (-> default-lib
                  (merge (:manually-added options))
                  (assoc :manually-added true))
              (assoc default-lib
                     :git-sha (fs/file-name lib-dir)))
        nodes (-> "test-resources/lib_tests/bb-tested-libs.edn" slurp r/parse-string)]
    (spit "test-resources/lib_tests/bb-tested-libs.edn"
         (str (r/assoc-in nodes
                          [(symbol lib-name)]
                          lib)))
    namespaces))

(defn- fetch-artifact
  "Using the clojars api to get a library's git url doesn't always work. A
  possibly more reliable data source could be the scm urls in this POM feed -
  https://github.com/clojars/clojars-web/wiki/Data#useful-extracts-from-the-poms"
  [artifact]
  (let [url (str "https://clojars.org/api/artifacts/" artifact)
        _ (println "GET" url "...")
        resp @(http/get url {:headers {"Accept" "application/edn"}})]
    (if (= 200 (:status resp))
      (-> resp :body slurp edn/read-string)
      (error (str "Response failed and returned " (pr-str resp))))))

(defn- get-lib-map
  [deps-string options]
  ;; if deps-string is artifact name
  (if (re-matches #"\S+/\S+" deps-string)
    (let [artifact-edn (fetch-artifact deps-string)]
      {:lib-name (symbol deps-string)
       :lib-coordinate {:mvn/version (:latest_version artifact-edn)}
       :git-url (or (:git-url options) (:homepage artifact-edn))})
    (let [deps-map (edn/read-string deps-string)
          _ (when (or (not (map? deps-map)) (not= 1 (count deps-map)))
              (error "Deps map must have one key"))
          lib-coordinate (-> deps-map vals first)]
      {:lib-name (ffirst deps-map)
       :lib-coordinate lib-coordinate
       :git-url (or (:git/url lib-coordinate) (:git-url options))})))

(defn- write-lib-to-csv
  "Updates libraries.csv with latest bb-tested-libs.edn"
  []
  (let [libs (-> "test-resources/lib_tests/bb-tested-libs.edn" slurp edn/read-string)
        rows (sort-by first
                      (map (fn [[name {:keys [git-url]}]]
                             [name git-url]) libs))]
    (with-open [w (io/writer "doc/libraries.csv")]
      (csv/write-csv w (into [["maven-name" "git-url"]] rows)))))

(defn- add-libtest*
  [args options]
  (let [[artifact-or-deps-string] args
        {:keys [lib-name lib-coordinate git-url]}
        (get-lib-map artifact-or-deps-string options)
        _ (when (nil? git-url)
            (error "git-url is required. Please specify with --git-url"))
        _ (when-not (:manually-added options) (add-lib-to-deps lib-name lib-coordinate))
        dirs (when-not (:manually-added options) (copy-tests git-url lib-name options))
        namespaces (add-lib-to-tested-libs lib-name git-url dirs options)]
    (println "Added lib" lib-name "which tests the following namespaces:" namespaces)
    (write-lib-to-csv)
    (when (:test options)
      (apply shell "script/lib_tests/run_all_libtests" namespaces))))

(defn add-libtest
  [{:keys [arguments options summary]}]
  (if (or (< (count arguments) 1) (:help options))
    (print-summary "ARTIFACT-OR-DEPS-MAP " summary)
    (add-libtest* arguments options)))

(def cli-options
  [["-h" "--help"]
   ["-t" "--test" "Run tests"]
   ;; https://github.com/weavejester/environ/tree/master/environ used this option
   ["-d" "--directory DIRECTORY" "Directory where library is located"]
   ;; https://github.com/reifyhealth/specmonstah used this option
   ["-b" "--branch BRANCH" "Default branch for git url"]
   ["-g" "--git-url GITURL" "Git url for artifact. Defaults to homepage on clojars"]
   ["-m" "--manually-added LIB-MAP" "Only add library to edn file with LIB-MAP merged into library entry"
    :parse-fn edn/read-string :validate-fn map?]
   ;; https://github.com/jeaye/orchestra used this option
   ["-T" "--test-directories TEST-DIRECTORY" "Directories where library tests are located"
    :multi true :update-fn conj]])

(when (= *file* (System/getProperty "babashka.file"))
  (run-command add-libtest *command-line-args* cli-options))
