(ns babashka.impl.ordered
  {:no-doc true}
  (:require [flatland.ordered.map :as omap]
            [flatland.ordered.set :as oset]
            [sci.core :as sci]))

(def omap-ns (sci/create-ns 'flatland.ordered.map nil))
(def oset-ns (sci/create-ns 'flatland.ordered.set nil))

(def ordered-map-ns
  {'ordered-map (sci/copy-var omap/ordered-map omap-ns)})

(def ordered-set-ns
  {'ordered-set (sci/copy-var oset/ordered-set oset-ns)})
