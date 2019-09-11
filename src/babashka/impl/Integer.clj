(ns babashka.impl.Integer
  {:no-doc true}
  (:refer-clojure :exclude [list]))

(set! *warn-on-reflection* true)

(defn parseInt
  ([^String x] (Integer/parseInt x))
  ([^String x ^long radix]
   (Integer/parseInt x radix)))

(def integer-bindings
  {'Integer/parseInt parseInt})

(comment
  
  )
