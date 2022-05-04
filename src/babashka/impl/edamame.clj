(ns babashka.impl.edamame
  (:require [edamame.core]
            [sci.core :as sci]))

(def ens (sci/create-ns 'edamame.core))

(def edamame-namespace (sci/copy-ns edamame.core ens))
