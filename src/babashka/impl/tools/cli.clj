(ns babashka.impl.tools.cli
  {:no-doc true}
  (:require [clojure.tools.cli :as tools.cli]
            [sci.impl.namespaces :refer [copy-var]]
            [sci.impl.vars :as vars]))

(def cli-ns (vars/->SciNamespace 'clojure.tools.cli nil))

(def tools-cli-namespace
  {'format-lines (copy-var tools.cli/format-lines cli-ns)
   'summarize (copy-var tools.cli/summarize cli-ns)
   'get-default-options (copy-var tools.cli/get-default-options cli-ns)
   'parse-opts (copy-var tools.cli/parse-opts cli-ns)})
