(ns babashka.impl.yaml
  {:no-doc true}
  (:require [clj-yaml.core :as yaml]
            [sci.impl.namespaces :refer [copy-var]]
            [sci.impl.vars :as vars]))

(def yns (vars/->SciNamespace 'clj-yaml.core nil))

(def yaml-namespace
  {'generate-string (copy-var yaml/generate-string yns)
   'parse-string (copy-var yaml/parse-string yns)})
