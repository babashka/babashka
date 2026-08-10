(ns babashka.impl.quickjs
  {:no-doc true}
  (:require [sci.core :as sci])
  (:import [io.roastedroot.quickjs4j.core Runner]))

(set! *warn-on-reflection* true)

(def qns (sci/create-ns 'babashka.js nil))

(defn runner
  "Returns a new JS runner. Close it when done."
  ^Runner []
  (.build (Runner/builder)))

(defn eval-str
  "Evaluates JS source in a fresh runner and returns whatever it wrote to
  stdout."
  [^String source]
  (with-open [^Runner r (runner)]
    (.compileAndExec r source)
    (.stdout r)))

(defn compile-str
  "Compiles JS source to QuickJS bytecode."
  [^String source]
  (with-open [^Runner r (runner)]
    (.compile r source)))

(defn exec-bytecode
  "Executes QuickJS bytecode from [[compile-str]] and returns stdout."
  [^bytes bytecode]
  (with-open [^Runner r (runner)]
    (.exec r bytecode)
    (.stdout r)))

(def quickjs-namespace
  {'runner (sci/copy-var runner qns)
   'eval-str (sci/copy-var eval-str qns)
   'compile-str (sci/copy-var compile-str qns)
   'exec-bytecode (sci/copy-var exec-bytecode qns)})
