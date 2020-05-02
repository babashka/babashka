(ns babashka.impl.datascript
  {:no-doc true}
  (:require [datascript.core :as d]
            [sci.impl.namespaces :refer [copy-var]]
            [sci.impl.vars :as vars]))

(def datascript-ns (vars/->SciNamespace 'datascript.core nil))

(def datascript-namespace
  {'create-conn (copy-var d/create-conn datascript-ns)
   'transact!   (copy-var d/transact! datascript-ns)
   'q           (copy-var d/q datascript-ns)})
