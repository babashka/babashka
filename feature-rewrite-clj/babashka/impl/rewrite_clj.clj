(ns babashka.impl.rewrite-clj
  {:no-doc true}
  (:require [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]
            [sci.core :as sci :refer [copy-var]]))

(def nns (sci/create-ns 'rewrite-clj.node nil))
(def pns (sci/create-ns 'rewrite-clj.parser nil))

(def node-namespace
  {'tag (copy-var n/tag nns)})

(def parser-namespace
  {'parse-string (copy-var p/parse-string pns)})
