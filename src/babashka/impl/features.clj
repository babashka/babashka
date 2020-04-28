(ns babashka.impl.features
  {:no-doc true})

(def xml? (not= "false" (System/getenv "BABASHKA_FEATURE_XML"))) ;; included by default
(def hsqldb? (= "true" (System/getenv "BABASHKA_FEATURE_HSQLDB"))) ;; excluded by default
