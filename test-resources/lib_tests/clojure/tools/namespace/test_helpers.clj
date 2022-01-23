(ns clojure.tools.namespace.test-helpers
  "Utilities to help with testing files and namespaces."
  (:require [clojure.java.io :as io]
            [clojure.string :as string])
  (:import (java.io Closeable File Writer PrintWriter)))

(defn create-temp-dir
  "Creates and returns a new temporary directory java.io.File."
  [name]
  (let [temp-file (File/createTempFile name nil)]
    (.delete temp-file)
    (.mkdirs temp-file)
    (println "Temporary directory" (.getAbsolutePath temp-file))
    temp-file))

(defn- write-contents
  "Writes contents into writer. Strings are written as-is via println,
  other types written as by prn."
  [^Writer writer contents]
  {:pre [(sequential? contents)]}
  (binding [*out* (PrintWriter. writer)]
    (doseq [content contents]
      (if (string? content)
        (println content)
        (prn content))
      (newline))))

(defn create-file
  "Creates a file from a vector of path elements. Writes contents into
  the file. Elements of contents may be data, written via prn, or
  strings, written directly."
  [path contents]
  {:pre [(vector? path)]}
  (let [^File file (apply io/file path)]
    (when-let [parent (.getParentFile file)]
      (.mkdirs parent))
    (with-open [wtr (io/writer file)]
      (write-contents wtr contents))
    file))

(defn- sym->path
  "Converts a symbol name into a vector of path parts, not including
  file extension."
  [symbol]
  {:pre [(symbol? symbol)]
   :post [(vector? %)]}
  (-> (name symbol)
      (string/replace \- \_)
      (string/split #"\.")))

(defn- source-path
  "Returns a vector of path components for namespace named sym,
  with given file extension (keyword)."
  [sym extension]
  (let [path (sym->path sym)
        basename (peek path)
        filename (str basename \. (name extension))]
    (conj (pop path) filename)))

(defn create-source
  "Creates a file at the correct path under base-dir for a namespace
  named sym, with file extension (keyword), containing a ns
  declaration which :require's the dependencies (symbols). Optional
  contents written after the ns declaration as by write-contents."
  ([base-dir sym extension]
   (create-source base-dir sym extension nil nil))
  ([base-dir sym extension dependencies]
   (create-source base-dir sym extension dependencies nil))  
  ([base-dir sym extension dependencies contents]
   (let [full-path (into [base-dir] (source-path sym extension))
         ns-decl (if (seq dependencies)
                   (list 'ns sym (list* :require dependencies))
                   (list 'ns sym))]
     (create-file full-path (into [ns-decl] contents)))))

(defn same-files?
  "True if files-a and files-b contain the same canonical File's,
  regardless of order."
  [files-a files-b]
  (= (sort (map #(.getCanonicalPath ^File %) files-a))
     (sort (map #(.getCanonicalPath ^File %) files-b))))

