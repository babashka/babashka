(ns babashka.impl.selmer-parser
  (:require [selmer.parser :as parser]
            [sci.core :as sci :refer [copy-var]]))

(def pns (sci/create-ns 'selmer.parser nil))

(def selmer-parser-namespace
  {:obj pns
   'render-file (copy-var parser/render-file pns)
   'set-resource-path! (copy-var parser/set-resource-path! pns)})
