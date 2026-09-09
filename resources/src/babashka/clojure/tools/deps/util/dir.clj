;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns
  clojure.tools.deps.util.dir
  "An abstraction for managing a 'current directory' stack.
  The dynamic var `*the-dir*` holds the current directory.
  Use `with-dir` to push a new absolute (or more usefully)
  relative directory onto the stack, which will pop when the
  scope exits. Use `canonical` to get the canonical File
  of a path in terms of the current directory context."
  (:require
    [clojure.java.io :as jio])
  (:import
    [java.io File]
    [java.nio.file Files Path]))

(set! *warn-on-reflection* true)

(def ^:dynamic ^File *the-dir*
  "Thread-local directory context for resolving relative directories.
  Defaults to current directory. Should always hold an absolute directory
  java.io.File, never null."
  (jio/file (System/getProperty "user.dir")))

(defn canonicalize
  "Make canonical File in terms of the current directory context.
  f may be either absolute or relative."
  ^File [^File f]
  (.getCanonicalFile
    (if (.isAbsolute f)
      f
      (jio/file *the-dir* f))))

(defmacro with-dir
  "Push directory into current directory context for execution of body."
  [^File dir & body]
  `(binding [*the-dir* (canonicalize ~dir)]
     ~@body))

(defn- same-file?
  "If a file can't be read (most common reason is directory does not exist), then
  treat this as not the same file (ie unknown)."
  [^Path p1 ^Path p2]
  (try
    (Files/isSameFile p1 p2)
    (catch Exception _ false)))

(defn sub-path?
  "True if the path is a sub-path of the current directory context.
  path may be either absolute or relative. Will return true if path
  has a parent that is the current directory context, false otherwise.
  Handles relative paths, .., ., etc. The sub-path does not need to
  exist on disk (but the current directory context must)."
  [^File path]
  (if (nil? path)
    false
    (let [root-path (.toPath ^File *the-dir*)]
      (loop [check-path (.toPath (canonicalize path))]
        (cond
          (nil? check-path) false
          (same-file? root-path check-path) true
          :else (recur (.getParent check-path)))))))

;; DEPRECATED
(defn as-canonical
  ^File [^File dir]
  (canonicalize dir))