(ns babashka.impl.datafy
  {:no-doc true}
  (:require
   [clojure.datafy :as d]
   [sci.core :as sci :refer [copy-var]]))

(def datafy-ns (sci/create-ns 'clojure.datafy nil))

(def datafy-namespace
  {'datafy (copy-var d/datafy datafy-ns)
   'nav (copy-var d/nav datafy-ns)})
