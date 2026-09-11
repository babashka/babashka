#!/usr/bin/env bb
;; nREPL's own test suite against the bundled server, run in the library's
;; checkout at the version bb bundles. The tests close a server with
;; with-open; the bundled Server record has no close, so those forms go
;; through with-server, and the hints naming the record and the transport
;; type are dropped. The completion tests are not run: nrepl.util.completion
;; is bb's own. A directory on the classpath overrides bundled
;; sources, so only the test directory is on it and nrepl.spec is loaded
;; by path. Run: ./bb script/nrepl_tests.clj [namespace ...]
(ns nrepl-tests
  (:require [babashka.classpath :as cp]
            [babashka.deps :as deps]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(def git-sha "61a42f454160d8a503fa8365fbd847ae3e797e8c") ; v1.7.0
(def git-url "https://github.com/nrepl/nrepl")

(def sources
  "Test files in load order, each with its substitutions."
  [["test/clojure/nrepl/test_helpers.clj" []]
   ["test/clojure/nrepl/core_test.clj"
    [["(require 'nrepl.spec) ;; Load for side effects (register specs)."
      "(load-file \"src/clojure/nrepl/spec.clj\")
(defmacro with-server [[sym expr] & body]
  `(let [~sym ~expr] (try ~@body (finally (server/stop-server ~sym)))))"]
     ["(with-open [^Server server (server/start-server :transport-fn transport-fn)]"
      "(with-server [server (server/start-server :transport-fn transport-fn)]"]
     ["(with-open [^Server s (server/start-server :transport-fn *transport-fn*"
      "(with-server [s (server/start-server :transport-fn *transport-fn*"]
     ["(with-open [^Server s2 (server/start-server :transport-fn *transport-fn*"
      "(with-server [s2 (server/start-server :transport-fn *transport-fn*"]
     ["(.close *server*)" "(server/stop-server *server*)"]
     ["(.close server)" "(server/stop-server server)"]
     ;; bb reports a reader error as its message, not in Clojure's phase words
     ["#\"(?s)^Syntax error reading source at[^\\n]+[\\r]?\\nMap literal must contain an even number of forms[\\r]?\\n\""
      "#\"Map literals? must contain an even number of forms\""]]]
   ["test/clojure/nrepl/middleware/session_test.clj" []]
   ["test/clojure/nrepl/middleware/interruptible_eval_test.clj" []]
   ["test/clojure/nrepl/middleware/load_file_test.clj" []]
   ["test/clojure/nrepl/describe_test.clj" []]
   ["test/clojure/nrepl/edn_test.clj"
    [["(with-open [^Server server (server/start-server :transport-fn transport/edn"
      "(nrepl.core-test/with-server [server (server/start-server :transport-fn transport/edn"]]]
   ["test/clojure/nrepl/sanity_test.clj" []]
   ["test/clojure/nrepl/response_test.clj" []]
   ["test/clojure/nrepl/middleware_test.clj" []]
   ["test/clojure/nrepl/misc_test.clj" []]
   ["test/clojure/nrepl/util/lookup_test.clj"
    ;; sci's let carries no :special-form metadata
    [["          :special-form \"true\"}\n         (lookup 'clojure.core 'let)"
      "          :macro \"true\"}\n         (lookup 'clojure.core 'let)"]]]
   ["test/clojure/nrepl/middleware/print_test.clj" []]
   ["test/clojure/nrepl/transport_test.clj" []]])

(def namespaces
  '[nrepl.core-test
    nrepl.middleware.session-test
    nrepl.middleware.interruptible-eval-test
    nrepl.middleware.load-file-test
    nrepl.describe-test
    nrepl.edn-test
    nrepl.sanity-test
    nrepl.response-test
    nrepl.middleware-test
    nrepl.misc-test
    nrepl.util.lookup-test
    nrepl.middleware.print-test
    nrepl.transport-test])

(def skipped
  "Tests of what the image does not have: Clojure's DynamicClassLoader, a
  JVMTI agent to stop a thread, java.util.GregorianCalendar, and JVM stack
  frames named after compiled functions."
  '{nrepl.core-test [hotloading-common-classloader-test
                     non-interruptible-stop-thread
                     session-*out*-writer-length-translation]
    nrepl.middleware.interruptible-eval-test [preserves-source-location-test]
    ;; walks every public and reads its var metadata; babashka's user/*input*
    ;; is an evaluated value rather than a var
    nrepl.util.lookup-test [bencode-test]})

(def runner
  "Runs in the checkout: the sources with their substitutions, then the tests."
  '(do
     (require '[clojure.string :as str] '[clojure.test :as t])
     (doseq [[file substs] 'SOURCES]
       (load-string
        (-> (reduce (fn [s [from to]]
                      (assert (str/includes? s from) (str file ": " from))
                      (str/replace s from to))
                    (slurp file)
                    substs)
            (str/replace #"\^(nrepl\.transport\.FnTransport|nrepl\.server\.Server|Server)\s+" ""))))
     (doseq [[ns vars] 'SKIPPED, v vars]
       (ns-unmap ns v))
     ;; clojure.main binds these for a JVM test run, the fixture set!s them
     (let [{:keys [fail error]} (binding [*print-length* nil *print-level* nil]
                                  (apply t/run-tests 'NAMESPACES))]
       (System/exit (if (zero? (+ fail error)) 0 1)))))

(let [checkout (fs/file (fs/home) ".gitlibs" "libs" "nrepl" "nrepl" git-sha)
      _ (when-not (fs/exists? checkout)
          (println "Fetching nrepl/nrepl at" git-sha)
          (p/shell "git" "clone" "-q" git-url (str checkout))
          (p/shell "git" "-C" (str checkout) "checkout" "-q" git-sha))
      bb (str (fs/absolutize (if (fs/windows?) "bb.exe" "bb")))
      _ (deps/add-deps '{:deps {nubank/matcher-combinators {:mvn/version "3.9.1"}}})
      ;; test/ for the resources the print tests read, target/ for the socket test
      _ (fs/create-dirs (fs/file checkout "target"))
      classpath (str/join fs/path-separator
                          (concat [(str (fs/file checkout "test" "clojure"))
                                   (str (fs/file checkout "test"))]
                                  (map #(str (fs/absolutize %)) (some-> (cp/get-classpath) fs/split-paths))))
      selected (if (seq *command-line-args*) (mapv symbol *command-line-args*) namespaces)
      script (fs/file (fs/create-temp-dir) "run_nrepl_tests.clj")
      code (-> (pr-str runner)
               (str/replace "SOURCES" (pr-str sources))
               (str/replace "SKIPPED" (pr-str skipped))
               (str/replace "NAMESPACES" (pr-str selected)))
      _ (spit script code)
      {:keys [exit]} (p/shell {:dir (str checkout) :continue true} bb "-cp" classpath (str script))]
  (System/exit exit))
