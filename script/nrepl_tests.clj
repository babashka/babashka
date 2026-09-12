#!/usr/bin/env bb
;; Run upstream nREPL tests: ./bb script/nrepl_tests.clj [namespace ...]
(ns nrepl-tests
  (:require [babashka.classpath :as cp]
            [babashka.deps :as deps]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]))

;; The pin lives in the lib tests registry, like every other library bb tests.
(def lib (get (edn/read-string (slurp "test-resources/lib_tests/bb-tested-libs.edn"))
              'nrepl/nrepl))
(def git-sha (:git-sha lib))
(def git-url (:git-url lib))

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
     ;; Match bb's reader error message.
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
    ;; sci exposes let as a macro.
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
  "Upstream tests excluded from the bundled server test run."
  '{nrepl.core-test [hotloading-common-classloader-test
                     non-interruptible-stop-thread
                     session-*out*-writer-length-translation]
    nrepl.middleware.interruptible-eval-test [preserves-source-location-test]
    ;; babashka's user/*input* is an evaluated value.
    nrepl.util.lookup-test [bencode-test]})

(def runner
  "Test runner form evaluated in the nREPL checkout."
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
     ;; The fixture requires thread bindings for set!.
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
      ;; The socket test requires target/.
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
