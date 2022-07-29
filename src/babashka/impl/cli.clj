(ns babashka.impl.cli
  (:require
   [babashka.cli]
   [sci.core :as sci]))

(def cns (sci/create-ns 'babashka.cli))

(def cli-namespace
  (sci/copy-ns babashka.cli cns))
