(ns ^{:skip-wiki true}
  clojure.tools.deps.extensions.maven
  "BB-STAND-IN for the tools.deps namespace of the same name, which
  implements the :mvn procurer over maven-resolver. clojure.tools.deps loads
  this path, so requiring babashka.mvn.tools-deps here registers the
  Maven-free :mvn and :pom methods in its place."
  (:require [babashka.mvn.tools-deps]))
