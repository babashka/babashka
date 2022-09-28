(ns babashka.impl.data
  {:no-doc true}
  (:require
   [babashka.impl.clojure.data :as data]
   [sci.core :as sci :refer [copy-var]]))

(def data-ns (sci/create-ns 'clojure.data nil))

(def data-namespace
  {'diff       (copy-var data/diff data-ns)})
