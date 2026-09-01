(ns babashka.impl.tools-deps
  {:no-doc true}
  (:require [clojure.tools.deps :as deps]
            [clojure.tools.deps.util.maven :as mvn]
            [sci.core :as sci])
  (:import [eu.maveniverse.maven.mima.runtime.standalonestatic StandaloneStaticRuntime]))

;; MIMA picks its runtime with a ServiceLoader, which native-image does not
;; register. Only one provider ships, so bind it here, at build time.
(alter-var-root #'mvn/the-runtime (constantly (delay (StandaloneStaticRuntime.))))

(def tns (sci/create-ns 'clojure.tools.deps nil))

(def tools-deps-namespace
  {'calc-basis (sci/copy-var deps/calc-basis tns)
   'create-basis (sci/copy-var deps/create-basis tns)
   'find-latest-version (sci/copy-var deps/find-latest-version tns)
   'join-classpath (sci/copy-var deps/join-classpath tns)
   'lib-location (sci/copy-var deps/lib-location tns)
   'make-classpath-map (sci/copy-var deps/make-classpath-map tns)
   'print-tree (sci/copy-var deps/print-tree tns)
   'resolve-added-libs (sci/copy-var deps/resolve-added-libs tns)
   'resolve-deps (sci/copy-var deps/resolve-deps tns)
   'root-deps (sci/copy-var deps/root-deps tns)
   'user-deps-path (sci/copy-var deps/user-deps-path tns)})
