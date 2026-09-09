(ns ^{:skip-wiki true}
  clojure.tools.deps.extensions.pom
  "BB-STAND-IN for the tools.deps namespace of the same name. The :pom
  extension methods live in babashka.impl.mvn.tools-deps. read-model and
  model-deps are here for extensions.local."
  (:require [babashka.impl.mvn.tools-deps :as mvn]))

(defn read-model
  "The effective model of a POM given as text, its parents from the
  repositories in config."
  [text config _settings]
  (mvn/model-from-text text config))

(defn model-deps
  "The compile and runtime dependencies of a model, as tools.deps data."
  [model]
  (mvn/model-deps model))
