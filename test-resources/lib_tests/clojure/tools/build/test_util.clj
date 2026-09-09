;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns clojure.tools.build.test-util
  (:require
    [clojure.java.io :as jio]
    [clojure.string :as str]
    [clojure.test :as test]
    [clojure.tools.build.util.file :as file])
  (:import
    [java.io File]))

(def ^:dynamic ^File *test-dir* nil)

;; BB-TEST-PATCH: the tests run from babashka's root; project data comes
;; from the tools.build clone the lib-test runner fetched, and the output
;; goes to the temp dir
(def tools-build-root
  (let [sha (-> (jio/resource "bb-tested-libs.edn") slurp read-string
                (get 'io.github.clojure/tools.build) :git-sha)]
    (jio/file (System/getProperty "user.home") ".gitlibs" "libs" "io.github.clojure" "tools.build" sha)))

(defmacro with-test-dir
  [test-project & body]
  `(let [name# (-> test/*testing-vars* last symbol str)
         dir# (jio/file (System/getProperty "java.io.tmpdir") "tools-build-test-out" name#)]
     (file/delete dir#)
     (.mkdirs dir#)
     (file/copy-contents (let [f# (jio/file ~test-project)]
                           (if (.isAbsolute f#) f# (jio/file tools-build-root f#)))
                         dir#)
     (binding [*test-dir* dir#]
       ~@body)))

(defn project-path [& parts]
  (str/join "/" (cons (.getAbsolutePath *test-dir*) parts)))

(defn submap?
  "Is m1 a subset of m2?"
  [m1 m2]
  (if (and (map? m1) (map? m2))
    (every? (fn [[k v]] (and (contains? m2 k)
                          (submap? v (get m2 k))))
      m1)
    (= m1 m2)))

(def windows? (str/starts-with? (System/getProperty "os.name") "Windows"))
