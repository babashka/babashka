#!/usr/bin/env bb
;; tools.deps's own test suite against the bundled tools.deps and bb's
;; Maven layer, run in the library's checkout at the version bb bundles.
;; The test helper faken imports Aether's GenericVersionScheme; it is loaded
;; with that swapped for bb's version port. Run: ./bb -cp resources/src/babashka script/mvn_oracle/tools_deps_test.clj
(ns tools-deps-test
  (:require [babashka.classpath :as cp]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(def git-sha "6c6e293b50fdbc6ea44e2a2ad246b90eb94f2539") ; v0.31.1638
(def git-url "https://github.com/clojure/tools.deps")
(def namespaces '[clojure.tools.deps.test-deps
                  clojure.tools.deps.extensions.test-git
                  clojure.tools.deps.extensions.test-pom
                  clojure.tools.deps.script.test-parse
                  clojure.tools.deps.script.test-make-classpath2])

(def runner
  "Runs in the checkout: faken with its Aether import swapped, then the tests."
  '(do
     (require '[clojure.string :as str] '[clojure.test :as t] 'babashka.impl.mvn.version)
     (let [subst (fn [s from to] (assert (str/includes? s from) from) (str/replace s from to))
           src (-> (slurp "src/test/clojure/clojure/tools/deps/extensions/faken.clj")
                   (subst "\n  (:import\n    ;; maven-resolver-util\n    [org.eclipse.aether.util.version GenericVersionScheme])" "")
                   (subst "(defonce ^:private version-scheme (GenericVersionScheme.))" "")
                   (subst "(defn- parse-version [{version :fkn/version :as _coord}]\n  (.parseVersion ^GenericVersionScheme version-scheme ^String version))" "")
                   (subst "(apply compare (map parse-version [coord-x coord-y]))"
                          "(babashka.impl.mvn.version/compare-versions (:fkn/version coord-x) (:fkn/version coord-y))"))]
       (load-string src))
     (apply require 'NAMESPACES)
     (let [{:keys [fail error]} (apply t/run-tests 'NAMESPACES)]
       (System/exit (if (zero? (+ fail error)) 0 1)))))

(if (fs/windows?)
  (println "tools.deps tests: skipped on Windows")
  (let [checkout (fs/file (fs/home) ".gitlibs" "libs" "org.clojure" "tools.deps" git-sha)
        _ (when-not (fs/exists? checkout)
            (println "Fetching org.clojure/tools.deps at" git-sha)
            (p/shell "git" "clone" "-q" git-url (str checkout))
            (p/shell "git" "-C" (str checkout) "checkout" "-q" git-sha))
        bb (str (fs/absolutize (if (fs/windows?) "bb.exe" "bb")))
        ;; the tree's or the image's sources, as this process got them
        classpath (str/join fs/path-separator
                            (cons (str (fs/file checkout "src" "test" "clojure"))
                                  (map #(str (fs/absolutize %)) (some-> (cp/get-classpath) fs/split-paths))))
        script (fs/file (fs/create-temp-dir) "run_tools_deps_tests.clj")
        code (str/replace (pr-str runner) "NAMESPACES" (pr-str namespaces))
        _ (spit script code)
        ;; a user deps.edn would enter every basis the tests build
        {:keys [exit]} (p/shell {:dir (str checkout) :continue true
                                 :extra-env {"CLJ_CONFIG" (str (fs/create-temp-dir))}}
                                bb "-cp" classpath (str script))]
    (System/exit exit)))
