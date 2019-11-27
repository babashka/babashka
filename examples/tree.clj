#!/usr/bin/env bb

;; to run with clojure: clojure -Sdeps '{:deps {org.clojure/tools.cli {:mvn/version "0.4.2"}}}' examples/tree.clj src
;; to run with babashka: examples/tree.clj src

(ns tree
  "Tree command, inspired by https://github.com/lambdaisland/birch."
  (:require [clojure.java.io :as io]
            [clojure.tools.cli :refer [parse-opts]]))

(def I-branch "│   ")

(def T-branch "├── ")

(def L-branch "└── ")

(def SPACER   "    ")

(defn file-tree
  [^java.io.File path]
  (let [children (.listFiles path)
        dir? (.isDirectory path)]
    (cond->
     {:name (.getName path)
      :type (if dir? "directory" "file")}
      dir? (assoc :contents
                  (map file-tree children)))))

(defn render-tree
  [{:keys [:name :contents]}]
  (cons name
        (mapcat
         (fn [child index]
           (let [subtree (render-tree child)
                 last? (= index (dec (count contents)))
                 prefix-first (if last? L-branch T-branch)
                 prefix-rest  (if last? SPACER I-branch)]
             (cons (str prefix-first (first subtree))
                   (map #(str prefix-rest %) (next subtree)))))
         contents
         (range))))

(defn stats
  [file-tree]
  (apply merge-with +
         {:total 1
          :directories (case (:type file-tree)
                         "directory" 1
                         0)}
         (map stats (:contents file-tree))))

(def cli-options [["-E" "--edn" "Output tree as EDN"]])

(defn -main [& args]
  (let [{:keys [options arguments]}
        (parse-opts args cli-options)
        path (io/file
              (or (first arguments)
                  "."))
        tree (file-tree path)
        {:keys [total directories]}
        (stats tree)]
    (if (:edn options)
      (prn tree)
      (do
        (doseq [l (render-tree tree)]
          (println l))
        (println)
        (println
         (str directories " directories, " (- total directories) " files"))))))

(apply -main *command-line-args*)

;;;; Scratch

(comment
  (-main "src" "-e" "-c"))
