;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns clojure.tools.build.util.file
  (:require
    [clojure.java.io :as jio]
    [clojure.string :as str])
  (:import
    [java.io File]
    [java.nio.file Path Files LinkOption CopyOption StandardCopyOption]
    [clojure.lang PersistentQueue]))

(set! *warn-on-reflection* true)

(defn collect-files
  "Recursively collect all paths under path, starting from root.
   Options:
    :dirs - whether to collect directories (default false)
    :collect - function for whether to collect a path (default yes)"
  [^File root & {:keys [dirs collect]
                 :or {dirs false
                      collect (constantly true)}}]
  (when (.exists root)
    (loop [queue (conj PersistentQueue/EMPTY root)
           collected []]
      (let [^File file (peek queue)]
        (if file
          (let [path (.toPath file)
                is-dir (Files/isDirectory path (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
                children (when is-dir (with-open [entries (Files/newDirectoryStream path)]
                                        (mapv #(.toFile ^Path %) entries)))
                collect? (and (if is-dir dirs true) (collect file))]
            (recur (into (pop queue) children) (if collect? (conj collected file) collected)))
          (when (seq collected) collected))))))

(defn suffixes
  "Returns a predicate matching suffixes"
  [& suffixes]
  (apply some-fn
    (map #(fn [^File f] (str/ends-with? (.toString f) ^String %)) suffixes)))

(defn delete
  "Recursively delete file, where file is coerced with clojure.java.io/file"
  [file]
  (run! #(.delete ^File %) (reverse (collect-files (jio/file file) :dirs true))))

(def ^{:private true, :tag "[Ljava.nio.file.CopyOption;"}
  copy-options
  (into-array CopyOption [StandardCopyOption/COPY_ATTRIBUTES StandardCopyOption/REPLACE_EXISTING]))

(defn copy-file
  "Copy file from src to target, retaining file attributes. Returns nil."
  [^File src-file ^File target-file]
  (.mkdirs target-file)
  (Files/copy (.toPath src-file) (.toPath target-file) copy-options)
  nil)

(defn copy-contents
  "Copy files in src dir to target dir, optionally filtering by prefix paths"
  ([^File src-dir ^File target-dir]
   (let [source-path (.toPath src-dir)
         target-path (.toPath target-dir)
         source-files (collect-files src-dir)]
     ;(println "source" (str source-path))
     ;(println "target" (str target-path))
     ;(println "source-files" (map str source-files))
     (run!
       (fn [^File f]
         (let [p (.toPath f)
               new-path (.resolve target-path (.relativize source-path p))]
           ;(println "copying" (str p) (str new-path))
           (copy-file f (.toFile new-path))))
       source-files)))
  ([^File src-dir ^File target-dir prefixes]
   (when (.exists src-dir)
     (let [root (.toPath src-dir)
           target (.toPath target-dir)]
       (loop [queue (conj PersistentQueue/EMPTY src-dir)]
         (let [^File file (peek queue)]
           (when file
             (let [path (.toPath file)
                   relative (.relativize root path)]
               ;(println "consider" (.toString file) "match" (some #(str/starts-with? (.toString relative) %) prefixes) "dir" (Files/isDirectory path (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])))
               (cond
                 ;; match, copy this file/dir
                 (some #(str/starts-with? (.toString relative) %) prefixes)
                 (let [end-path (.resolve target relative)]
                   (copy-contents file (.toFile end-path))
                   (recur (pop queue)))

                 ;; no match, but continue looking in this directory if it could match later
                 (and
                   (Files/isDirectory path (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
                   (some #(str/starts-with? % (.toString relative)) prefixes))
                 (recur (into (pop queue)
                          (with-open [entries (Files/newDirectoryStream path)]
                            (mapv #(.toFile ^Path %) entries))))

                 ;; work the queue
                 :else
                 (recur (pop queue)))))))))))

(defn ensure-dir
  "Ensure dir exists by making all parent directories and return it"
  ^File [dir]
  (let [d (jio/file dir)]
    (if (.exists d)
      d
      (if (.mkdirs d)
        d
        (throw (ex-info (str "Can't create directory " dir) {}))))))

(defn ensure-file
  ([file] (ensure-file file ""))
  ([file contents & opts]
   (let [file (jio/file file)
         parent (.getParent file)]
     (if (.exists (jio/file parent))
       (apply spit file contents opts)
       (do
         (ensure-dir parent)
         (apply spit file contents opts))))))
