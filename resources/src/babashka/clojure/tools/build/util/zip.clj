;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns clojure.tools.build.util.zip
  (:require
    [clojure.java.io :as jio]
    [clojure.tools.build.util.file :as file]
    [clojure.string :as str])
  (:import
    [java.io File InputStream OutputStream]
    [java.nio.file Files LinkOption]
    [java.nio.file.attribute BasicFileAttributes]
    [java.util.zip ZipFile ZipInputStream ZipOutputStream ZipEntry]
    [java.util.jar Manifest Attributes$Name]))

(set! *warn-on-reflection* true)

(defn- add-zip-entry
  [^ZipOutputStream output-stream ^String path ^File file]
  (let [dir (.isDirectory file)
        attrs (Files/readAttributes (.toPath file) BasicFileAttributes ^"[Ljava.nio.file.LinkOption;" (into-array LinkOption []))
        path (if (and dir (not (.endsWith path "/"))) (str path "/") path)
        path (str/replace path \\ \/) ;; only use unix-style paths in jars
        entry (doto (ZipEntry. path)
                ;(.setSize (.size attrs))
                ;(.setLastAccessTime (.lastAccessTime attrs))
                (.setLastModifiedTime (.lastModifiedTime attrs)))]
    (.putNextEntry output-stream entry)
    (when-not dir
      (with-open [fis (jio/input-stream file)]
        (jio/copy fis output-stream)))

    (.closeEntry output-stream)))

(defn copy-to-zip
  [^ZipOutputStream jos ^File root]
  (let [root-path (.toPath root)
        files (file/collect-files root :dirs true)]
    (run! (fn [^File f]
            (let [rel-path (.toString (.relativize root-path (.toPath f)))]
              (when-not (= rel-path "")
                ;(println "  Adding" rel-path)
                (add-zip-entry jos rel-path f))))
      files)))

(defn fill-manifest!
  [^Manifest manifest props]
  (let [attrs (.getMainAttributes manifest)]
    (run!
      (fn [[name value]]
        (.put attrs (Attributes$Name. ^String name) value)) props)))

(defn list-zip
  [^String zip-path]
  (let [zip-file (jio/file zip-path)]
    (when (.exists zip-file)
      (with-open [zip (ZipFile. zip-file)]
        (let [entries (enumeration-seq (.entries zip))]
          (sort-by :name
            (into [] (map (fn [^ZipEntry entry]
                            {:name (.getName entry)
                             :created (.getCreationTime entry)
                             :modified (.getLastModifiedTime entry)
                             :size (.getSize entry)}))
              entries)))))))

(defn copy-stream!
  "Copy input stream to output stream using buffer.
  Caller is responsible for passing buffered streams and closing streams."
  [^InputStream is ^OutputStream os ^bytes buffer]
  (loop []
    (let [size (.read is buffer)]
      (if (pos? size)
        (do
          (.write os buffer 0 size)
          (recur))
        (.close os)))))

(defn unzip
  [^String zip-path ^String target-dir]
  (let [buffer (byte-array 4096)
        zip-file (jio/file zip-path)]
    (if (.exists zip-file)
      (with-open [zis (ZipInputStream. (jio/input-stream zip-file))]
        (loop []
          (if-let [entry (.getNextEntry zis)]
            ;(println "entry:" (.getName entry) (.isDirectory entry))
            (let [out-file (jio/file target-dir (.getName entry))]
              (jio/make-parents out-file)
              (when-not (.isDirectory entry)
                (with-open [output (jio/output-stream out-file)]
                  (copy-stream! zis output buffer)
                  (Files/setLastModifiedTime (.toPath out-file) (.getLastModifiedTime entry))))
              (recur))
            true)))
      false)))
