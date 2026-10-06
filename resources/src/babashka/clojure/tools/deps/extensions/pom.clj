(ns ^{:skip-wiki true}
  clojure.tools.deps.extensions.pom
  "BB-STAND-IN for the tools.deps namespace of the same name. The :pom
  extension methods live in babashka.impl.mvn.tools-deps. read-model,
  read-model-file and model-deps are here for extensions.local and for
  callers of tools.deps."
  (:require [babashka.impl.mvn.tools-deps :as mvn]))

(defn read-model
  "The effective model of a POM given as text, its parents from the
  repositories in config."
  [text config _settings]
  (mvn/model-from-text text config))

(defn read-model-file
  "Returns the effective model of the POM in file, a parent from disk or from
  the repositories in config.
  Throws if Maven rejects the model."
  [file config]
  (mvn/model-from-file file config))

(defn model-deps
  "The compile and runtime dependencies of a model, as tools.deps data."
  [model]
  (mvn/model-deps model))
