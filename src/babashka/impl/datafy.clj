(ns babashka.impl.datafy
  {:no-doc true}
  (:refer-clojure :exclude [create-ns])
  (:require
   [babashka.impl.protocols :as protocols]
   [sci.core :as sci :refer [copy-var]]))

(def datafy-ns (sci/create-ns 'clojure.datafy nil))

(def datafy-namespace
  {'datafy (copy-var protocols/datafy datafy-ns)
   'nav (copy-var protocols/nav datafy-ns)})
