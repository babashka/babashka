(ns babashka.impl.File
  {:no-doc true}
  (:refer-clojure :exclude [list])
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(set! *warn-on-reflection* true)
;; also see https://gist.github.com/plexus/68594ba9b5e3f0d63fe84dcae31c4a53

(defmacro gen-wrapper-fn [method-name]
  `(defn ~method-name {:bb/export true}
     [~(with-meta 'x {:tag 'java.io.File})]
     (~(symbol (str "." method-name)) ~'x)))

(defmacro gen-wrapper-fn-2 [method-name]
  `(defn ~method-name {:bb/export true}
     [~(with-meta 'x {:tag 'java.io.File}) ~'y]
     (~(symbol (str "." method-name)) ~'x ~'y)))

(gen-wrapper-fn canExecute)
(gen-wrapper-fn canRead)
(gen-wrapper-fn canWrite)
(defn createTempFile
  ([^String prefix ^String suffix]
   (java.io.File/createTempFile prefix suffix))
  ([^String prefix ^String suffix ^java.io.File dir]
   (java.io.File/createTempFile prefix suffix dir)))
(gen-wrapper-fn delete)
(gen-wrapper-fn deleteOnExit)
(gen-wrapper-fn exists)
(gen-wrapper-fn getAbsoluteFile)
(gen-wrapper-fn getCanonicalFile)
(gen-wrapper-fn getCanonicalPath)
(gen-wrapper-fn getName)
(gen-wrapper-fn getParent)
(gen-wrapper-fn getParentFile)
(gen-wrapper-fn getPath)
(gen-wrapper-fn isAbsolute)
(gen-wrapper-fn isDirectory)
(gen-wrapper-fn isFile)
(gen-wrapper-fn isHidden)
(gen-wrapper-fn lastModified)
(gen-wrapper-fn length)
(gen-wrapper-fn list)
(gen-wrapper-fn listFiles)
(gen-wrapper-fn mkdir)
(gen-wrapper-fn mkdirs)
(gen-wrapper-fn-2 renameTo)
(defn ^:bb/export setExecutable
  ([^java.io.File f b]
   (.setExecutable f b))
  ([^java.io.File f b ownerOnly]
   (.setExecutable f b ownerOnly)))
(gen-wrapper-fn-2 setLastModified)
(gen-wrapper-fn-2 setReadable)
(gen-wrapper-fn setReadOnly)
(defn ^:bb/export setWritable
  ([^java.io.File f b]
   (.setWritable f b))
  ([^java.io.File f b ownerOnly]
   (.setWritable f b ownerOnly)))
(gen-wrapper-fn toPath)
(gen-wrapper-fn toURI)

(def file-bindings
  (-> (reduce (fn [acc [k v]]
                (if (-> v meta :bb/export)
                  (assoc acc (symbol (str "." k))
                         @v)
                  acc))
              {}
              (ns-publics *ns*))
      ;; static methods
      (assoc (symbol "File/createTempFile") createTempFile)
      (assoc (symbol "java.io.File/createTempFile") createTempFile)))

(comment
  (canRead (clojure.java.io/file "README.md"))
  (canWrite (clojure.java.io/file "README.md"))
  (exists (clojure.java.io/file "README.md"))
  (renameTo (io/file "/tmp/script2.clj") (io/file "/tmp/script.clj"))
  (.setWritable (io/file "/tmp/script.clj") true true)
  (meta #'toURI)
  (meta #'canWrite)
  ;; for README.md:
  (str/join ", " (map #(format "`%s`" %) (sort (keys file-bindings))))
  )
