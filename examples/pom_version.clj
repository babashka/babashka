#!/usr/bin/env bb

(ns pom-version
  {:author "Wilker Lucio"}
  (:require [clojure.data.xml :as xml]
            [clojure.string :as str]))

(defn tag-name? [tag tname]
  (some-> tag :tag name #{tname}))

(defn tag-content-str [tag]
  (->> tag :content (filter string?) (str/join "")))

(defn pom-version
  ([] (pom-version "pom.xml"))
  ([path]
   (->> (xml-seq (xml/parse-str (slurp path)))
        (filter #(tag-name? % "version"))
        first
        tag-content-str)))

(pom-version)
