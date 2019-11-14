(ns babashka.impl.Double
  {:no-doc true}
  (:refer-clojure :exclude [list]))

(set! *warn-on-reflection* true)

(defn parseDouble [^String x]
  (Double/parseDouble x))

(def double-bindings
  {'Double/parseDouble parseDouble})

(comment
  
  )
