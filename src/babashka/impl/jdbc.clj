(ns babashka.impl.jdbc
  {:no-doc true}
  (:require [clojure.java.jdbc :as jdbc]
            [sci.impl.namespaces :refer [copy-var]]
            [sci.impl.vars :as vars]))

(def tns (vars/->SciNamespace 'clojure.data.jdbc nil))

(def jdbc-namespace
  {'query (copy-var jdbc/query tns)})
