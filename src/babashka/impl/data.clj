(ns babashka.impl.data
  {:no-doc true}
  (:require [babashka.impl.clojure.data :as data]
            [sci.impl.namespaces :refer [copy-var]]
            [sci.impl.vars :as vars]))

(def data-ns (vars/->SciNamespace 'clojure.data nil))

(def data-namespace
  {'diff       (copy-var data/diff data-ns)})
