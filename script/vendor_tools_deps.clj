#!/usr/bin/env bb
;; Copy the tools.deps, tools.deps.edn and tools.gitlibs sources into
;; resources/src/babashka, where bb's load-fn serves bundled namespaces from.
(require '[babashka.fs :as fs])

(def m2 (str (fs/expand-home "~/.m2/repository/org/clojure")))

(def jars
  [(str m2 "/tools.deps/0.31.1638/tools.deps-0.31.1638.jar")
   (str m2 "/tools.deps.edn/0.9.42/tools.deps.edn-0.9.42.jar")
   (str m2 "/tools.gitlibs/2.6.217/tools.gitlibs-2.6.217.jar")])

;; specs is stubbed as a built-in namespace, the Maven-backed namespaces have
;; babashka.mvn stand-ins at the same paths, the rest is not needed for
;; make-classpath2.
(def skip
  #{"clojure/tools/deps/specs.clj"
    "clojure/tools/deps/gen/pom.clj"
    "clojure/tools/deps/script/generate_manifest2.clj"
    "clojure/tools/deps/util/maven.clj"
    "clojure/tools/deps/extensions/maven.clj"
    "clojure/tools/deps/extensions/pom.clj"
    "clojure/tools/deps/extensions/local.clj"})

(def target "resources/src/babashka")

(let [tmp (fs/create-temp-dir)]
  (doseq [jar jars]
    (fs/unzip jar tmp {:replace-existing true}))
  (doseq [f (fs/glob tmp "clojure/tools/**.clj")
          :let [rel (str (fs/relativize tmp f))]
          :when (not (skip rel))]
    (fs/create-dirs (fs/parent (fs/file target rel)))
    (fs/copy f (fs/file target rel) {:replace-existing true})
    (println rel))
  (fs/delete-tree tmp))
