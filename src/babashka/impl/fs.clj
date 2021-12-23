(ns babashka.impl.fs
  (:require [babashka.fs]
            [sci.core :as sci]))

(def fs-namespace
  (sci/copy-ns babashka.fs (sci/create-ns 'babashka.fs)))
