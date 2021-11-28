(ns babashka.impl.core
  {:no-doc true}
  (:require [babashka.core]
            [sci.core :as sci]))

(def cns (sci/create-ns 'babashka.core nil))

(def core-namespace
  (sci/copy-ns babashka.core cns))
