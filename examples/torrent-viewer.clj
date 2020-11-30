#!/usr/bin/env bb

(require '[clojure.java.io :as io])
(require '[bencode.core :refer [read-bencode]])
(require '[clojure.walk :refer [prewalk]])
(require '[clojure.pprint :refer [pprint]])
(import 'java.io.PushbackInputStream)

(defn bytes->strings [coll]
  (prewalk #(if (bytes? %) (String. % "UTF-8") %) coll))

(defn read-torrent [src]
  (with-open [in (io/input-stream (io/file src))]
    (-> in PushbackInputStream. read-bencode bytes->strings)))

(when-let [src (first *command-line-args*)]
  (-> (read-torrent src)
      (assoc-in ["info" "pieces"] "...") ; binary data
      pprint))
