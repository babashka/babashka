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
   (->>
    (slurp path)
    (xml/parse-str)
    (xml-seq )
    (filter #(tag-name? % "version"))
    first
    tag-content-str)))

(if-let [arg (first *command-line-args*)]
  (pom-version arg)
  (pom-version))
