(ns db-who
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.pprint :as pp]))
(defn tsv->maps [tsv]
  (let [lines (str/split-lines tsv)
        [headers & rows] (map #(str/split % #"\t") lines)]
    (map #(zipmap headers %) rows)))

(-> (shell/sh "mysql" "--column-names" "-e" "select user, program_name from sys.session;")
  :out tsv->maps pp/print-table)
