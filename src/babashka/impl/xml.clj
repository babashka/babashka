(ns babashka.impl.xml
  {:no-doc true}
  (:require [clojure.data.xml :as xml]
            [sci.impl.namespaces :refer [copy-var]]
            [sci.impl.vars :as vars]))

(def xns (vars/->SciNamespace 'clojure.data.xml nil))

(def xml-namespace
  {'parse-str (copy-var xml/parse-str xns)
   'element (copy-var xml/element xns)
   'emit-str (copy-var xml/emit-str xns)})
