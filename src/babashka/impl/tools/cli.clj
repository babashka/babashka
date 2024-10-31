(ns babashka.impl.tools.cli
  {:no-doc true}
  (:require [clojure.tools.cli :as tools.cli]
            [sci.core :as sci :refer [copy-var]]
            [sci.impl.utils :as sciu]))

(def cli-ns (sci/create-ns 'clojure.tools.cli nil))

(defn parse-opts [args option-specs & options]
  (prn :try sciu/*in-try*)
  (binding [sciu/*in-try* nil]
    (prn :parse-opts!)
    (apply tools.cli/parse-opts args option-specs options)))

(def tools-cli-namespace
  {'format-lines (copy-var tools.cli/format-lines cli-ns)
   'summarize (copy-var tools.cli/summarize cli-ns)
   'get-default-options (copy-var tools.cli/get-default-options cli-ns)
   'parse-opts (copy-var parse-opts #_tools.cli/parse-opts cli-ns)
   'make-summary-part (copy-var tools.cli/make-summary-part cli-ns)})
