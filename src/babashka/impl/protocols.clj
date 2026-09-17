(ns babashka.impl.protocols
  {:no-doc true}
  (:require [clojure.core.protocols :as p]
            [sci.core :as sci :refer [copy-var]]))

(def protocols-ns (sci/create-ns 'clojure.core.protocols nil))

(def protocols-namespace
  {'Datafiable (copy-var p/Datafiable protocols-ns)
   'datafy (copy-var p/datafy protocols-ns)
   'Navigable (copy-var p/Navigable protocols-ns)
   'nav (copy-var p/nav protocols-ns)
   'IKVReduce (copy-var p/IKVReduce protocols-ns)
   'kv-reduce (copy-var p/kv-reduce protocols-ns)
   'CollReduce (copy-var p/CollReduce protocols-ns)
   'coll-reduce (copy-var p/coll-reduce protocols-ns)})
