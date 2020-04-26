(ns babashka.impl.features
  {:no-doc true})

(def hsqldb? (= "true" (System/getenv "BABASHKA_FEATURE_HSQLDB")))
