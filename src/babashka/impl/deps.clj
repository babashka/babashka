(ns babashka.impl.deps
  (:require [babashka.impl.classpath :as cp]
            [borkdude.deps :as deps]
            [sci.core :as sci]))

(def dns (sci/create-ns 'dns nil))

(defn add-deps [deps-map]
  (let [cp (with-out-str (deps/-main "-Spath" "-Sdeps" (str {:deps deps-map})))]
    (cp/add-classpath cp)))

(def deps-namespace
  {'add-deps (sci/copy-var add-deps dns)})
