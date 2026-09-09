(ns babashka.mvn.xml
  "Reading Maven's XML files with data.xml. Tags are compared by local
  name, the POM and settings namespaces do not matter here."
  (:require [clojure.data.xml :as xml]
            [clojure.string :as str]))

(defn parse [s]
  (xml/parse-str s))

(defn tag= [tag el]
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
