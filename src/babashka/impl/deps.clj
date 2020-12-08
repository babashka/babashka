(ns babashka.impl.deps
  (:require [borkdude.deps :as deps]
            [sci.core :as sci]))

(def dns (sci/create-ns 'dns nil))

(defn add-deps [deps-map]
  (let [cp (deps/-main "-Spath" "-Sdeps" (str deps-map))]
    (prn :cp cp)))

(def deps-namespace
  {'add-deps (sci/copy-var add-deps dns)})
