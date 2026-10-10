(ns ^{:skip-wiki true}
  clojure.tools.deps.extensions.maven
  "BB-STAND-IN for the tools.deps namespace of the same name, which
  implements the :mvn procurer over maven-resolver. clojure.tools.deps loads
  this path, so requiring babashka.impl.mvn.tools-deps here registers the
  Maven-free :mvn and :pom methods in its place. find-versions is here, on
  the public babashka.deps.maven."
  (:require [babashka.deps.maven :as mvn]
            [babashka.impl.mvn.tools-deps]
            [clojure.tools.deps.extensions :as ext]))

(defmethod ext/find-versions :mvn
  [lib _coord _coord-type config]
  (mvn/find-versions lib config))
