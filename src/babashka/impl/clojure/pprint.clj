(ns babashka.impl.clojure.pprint
  {:no-doc true}
  (:require [fipp.edn :as fipp]))

(defn pprint
  ([edn]
   (fipp/pprint edn))
  ([edn writer]
   (fipp/pprint edn {:writer writer})))

(def pprint-namespace
  {'pprint pprint})
