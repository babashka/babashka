(ns clojure.repl.deps
  (:require [babashka.deps :as deps]))

(defn add-libs
  "Given lib-coords, a map of lib to coord, will resolve all transitive deps for the libs
  together and add them to the repl classpath, unlike separate calls to add-lib."
  {:added "1.12"}
  [lib-coords]
  (deps/add-deps {:deps lib-coords})
  nil)

(defn add-lib
  "Given a lib that is not yet on the repl classpath, make it available by
  downloading the library if necessary and adding it to the classloader.
  Libs already on the classpath are not updated. Requires a valid parent
  DynamicClassLoader.

   lib - symbol identifying a library, for Maven: groupId/artifactId
   coord - optional map of location information specific to the procurer,
           or latest if not supplied

  Returns coll of libs loaded, including transitive (or nil if none).

  For info on libs, coords, and versions, see:
   https://clojure.org/reference/deps_and_cli"
  {:added "1.12"}
  ([lib coord]
   (add-libs {lib coord}))
  ([lib]
   (throw (ex-info "add-lib without explicit version isn't supported in babashka (yet)" {:lib lib}))))

