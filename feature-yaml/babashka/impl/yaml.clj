(ns babashka.impl.yaml
  {:no-doc true}
  (:require [clj-yaml.core :as yaml]
            [sci.core :as sci :refer [copy-var]]))

(def yns (sci/create-ns 'clj-yaml.core nil))

(def yaml-namespace
  {'mark (copy-var yaml/mark yns)
   'unmark (copy-var yaml/unmark yns)
   'generate-string (copy-var yaml/generate-string yns)
   'parse-string (copy-var yaml/parse-string yns)
   'generate-stream (copy-var yaml/generate-stream yns)
   'parse-stream (copy-var yaml/parse-stream yns)
   })
