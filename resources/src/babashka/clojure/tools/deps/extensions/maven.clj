(ns ^{:skip-wiki true}
  clojure.tools.deps.extensions.maven
  "babashka's stand-in for the tools.deps namespace of the same name, which
  implements the :mvn procurer over maven-resolver. tools.deps loads this
  file last, so requiring babashka.mvn.tools-deps here registers the
  Maven-free :mvn and :pom methods once tools.deps' own are in place."
  (:require [babashka.mvn.tools-deps]))
