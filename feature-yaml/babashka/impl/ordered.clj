(ns babashka.impl.ordered
  {:no-doc true}
  (:require [flatland.ordered.map :as omap]
            [sci.core :as sci]))

(def omap-ns (sci/create-ns 'flatland.ordered.map nil))

(def ordered-map-ns
  {'ordered-map (sci/copy-var omap/ordered-map omap-ns)})
