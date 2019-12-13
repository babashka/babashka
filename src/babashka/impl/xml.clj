(ns babashka.impl.xml
  {:no-doc true}
  (:require [clojure.data.xml :as xml]))

(def xml-namespace
  {'parse-str xml/parse-str
   'element xml/element
   'emit-str xml/emit-str})

