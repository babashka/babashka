(ns babashka.impl.mvn.xml
  "Reading Maven's XML files with data.xml. Tags are compared by local
  name, the POM and settings namespaces do not matter here."
  {:no-doc true}
  (:require [babashka.impl.mvn.entities :as entities]
            [clojure.data.xml :as xml]
            [clojure.data.xml.tree :as tree]
            [clojure.string :as str]))

(defn parse
  "Parses XML text the way Maven's readers accept it: a byte order mark is
  skipped and the HTML character entities they know are resolved. The whole
  document is read before the tree is built, so an error anywhere in it
  throws here."
  [s]
  (let [s (if (str/starts-with? s "﻿") (subs s 1) s)
        s (str/replace s #"&([A-Za-z][A-Za-z0-9]*);"
                       (fn [[entity name]] (get entities/replacements name entity)))]
    (tree/event-tree (doall (xml/event-seq (java.io.StringReader. s) {})))))

(defn- tag= [tag el]
  (and (map? el) (= tag (name (:tag el)))))

(defn elements [el]
  (filter map? (:content el)))

(defn child [el tag]
  (first (filter #(tag= tag %) (elements el))))

(defn children [el tag]
  (filter #(tag= tag %) (elements el)))

(defn text [el]
  (when el
    (let [s (apply str (filter string? (:content el)))]
      (when-not (str/blank? s) (str/trim s)))))

(defn child-text [el tag]
  (text (child el tag)))

(defn tag-name [el]
  (name (:tag el)))
