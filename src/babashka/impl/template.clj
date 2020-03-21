(ns babashka.impl.template
  {:no-doc true}
  (:require [comb.template :as template]))

(def template-namespace
  {'eval template/eval})