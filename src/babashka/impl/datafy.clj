(ns babashka.impl.datafy
  {:no-doc true}
  (:refer-clojure :exclude [create-ns])
  (:require [clojure.datafy :as datafy]
            [sci.core :refer [create-ns copy-var]]))

(def datafy-ns (create-ns 'clojure.data nil))

(def datafy-namespace
  {'datafy (copy-var datafy/datafy datafy-ns)
   'nav (copy-var datafy/nav datafy-ns)})
