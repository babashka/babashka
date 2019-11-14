(ns babashka.impl.Boolean
  {:no-doc true}
  (:refer-clojure :exclude [list]))

(set! *warn-on-reflection* true)

(defn parseBoolean [^String x]
  (Boolean/parseBoolean x))

(def boolean-bindings
  {'Boolean/parseBoolean parseBoolean})

(comment
  
  )
