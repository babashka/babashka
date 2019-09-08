(ns babashka.impl.tools.cli
  {:no-doc true}
  (:require [clojure.tools.cli :as tools.cli]))

(def tools-cli-namespace
  {'format-lines tools.cli/format-lines
   'summarize tools.cli/summarize
   'get-default-options tools.cli/get-default-options
   'parse-opts tools.cli/parse-opts})
