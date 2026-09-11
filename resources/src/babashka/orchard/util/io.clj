(ns orchard.util.io
  "Utility functions for dealing with file system objects, and in/out streams."
  (:require
   [clojure.java.io :as io])
  (:import
   (java.io File)
   (java.net URL JarURLConnection)
   (java.nio.file Path LinkOption)))

(def ^:private ^Path cwd-path
  (let [path (.toPath (io/file ""))]
    (try (.toRealPath path (into-array LinkOption []))
         (catch Exception _
           ;; .toRealPath may throw in some cases, this is a fallback that
           ;; doesn't do normalization.
           (.toAbsolutePath path)))))

(defn- as-path ^Path [path-or-file-or-string]
  (if (instance? Path path-or-file-or-string)
    path-or-file-or-string
    (.toPath (io/as-file path-or-file-or-string))))

(defn file-in-project?
  "Return true if the given file (String or File) or Path belongs to the project
  directory (where the process was started)."
  [path-or-file]
  (.startsWith (as-path path-or-file) cwd-path))

(defn relativize-project-path
  "Given a file or a path which lives in current process' directory, return its
  relative path in the project as a String."
  [path-or-file]
  (str (.relativize cwd-path (as-path path-or-file))))

(defn url-protocol
  "Get the URL protocol as a string, e.g. http, file, jar."
  ^String
  [^java.net.URL url]
  (.getProtocol url))

(def jar? #{"jar"})

(def file? #{"file"})

(defn direct-url-to-file?
  "Does this URL point to a file directly?
  i.e., does it use the file: protocol."
  [^java.net.URL url]
  (file? (url-protocol url)))

(defn resource-jarfile
  "Given a jar:file:...!/... URL, return the location of the jar file on the
  filesystem. Returns nil on any other URL."
  ^File [^URL jar-resource]
  (assert (jar? (url-protocol jar-resource)))
  (let [^JarURLConnection conn (.openConnection jar-resource)
        inner-url (.getJarFileURL conn)]
    (when (file? (url-protocol inner-url))
      (io/as-file inner-url))))

(defn resource-artifact
  "Return the File from which the given resource URL would be loaded.

  For `file:` URLs returns the location of the resource itself, for
  `jar:..!/...` URLs returns the location of the archive containing the
  resource. Returns a fully qualified File, even when the URL is relative.
  Throws when the URL is not a `file:` or `jar:` URL."
  ^File [^java.net.URL resource]
  (let [protocol (url-protocol resource)]
    (cond
      (file? protocol)
      (io/as-file resource)

      (jar? protocol)
      (resource-jarfile resource)

      :else
      (throw (ex-info (str "URLs with a " protocol
                           " protocol can't be situated on the filesystem.")
                      {:resource resource})))))

(defprotocol LastModifiedTime
  (last-modified-time [this]
    "Return the last modified time of a File or resource URL."))

(extend-protocol LastModifiedTime
  java.net.URL
  (last-modified-time [this]
    (last-modified-time (resource-artifact this)))
  java.io.File
  (last-modified-time [this]
    (.lastModified this)))
