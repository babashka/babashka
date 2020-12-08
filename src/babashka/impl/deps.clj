(ns babashka.impl.deps
  (:require [babashka.impl.classpath :as cp]
            [borkdude.deps :as deps]
            [clojure.string :as str]
            [sci.core :as sci]))

(def dns (sci/create-ns 'dns nil))

(defn add-deps
  ([deps-map] (add-deps deps-map nil))
  ([deps-map {:keys [:aliases]}]
   (let [args ["-Spath" "-Sdeps" (str deps-map)]
         args (cond-> args
                aliases (conj (str "-A:" (str/join ":" aliases))))
         cp (with-out-str (apply deps/-main args))]
     (cp/add-classpath cp))))

(def deps-namespace
  {'add-deps (sci/copy-var add-deps dns)})
