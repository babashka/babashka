(ns babashka.impl.mvn.xml
  "Reading Maven's XML files with data.xml. Tags are compared by name, the
  POM and settings namespaces do not matter here."
  {:no-doc true}
  (:require [babashka.impl.mvn.entities :as entities]
            [clojure.data.xml :as xml]
            [clojure.data.xml.tree :as tree]
            [clojure.string :as str]))

(defn parse
  "Parses XML text as Maven's readers do: without namespaces, skipping a byte
  order mark and resolving the HTML character entities those readers know.
  Throws on an error anywhere in the document."
  [s]
  (let [s (if (str/starts-with? s "\uFEFF") (subs s 1) s)
        s (str/replace s #"&([A-Za-z][A-Za-z0-9]*);"
                       (fn [[entity name]] (get entities/replacements name entity)))]
    (tree/event-tree (doall (xml/event-seq (java.io.StringReader. s) {:namespace-aware false})))))

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

(defn true-text?
  "Whether s reads as true, ignoring case, as Maven's readers parse a boolean
  with Boolean/valueOf."
  [s]
  (.equalsIgnoreCase "true" ^String s))

(defn tag-name [el]
  (name (:tag el)))
