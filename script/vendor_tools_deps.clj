#!/usr/bin/env bb
;; Copy the tools.deps, tools.deps.edn and tools.gitlibs sources into
;; resources/src/babashka, where bb's load-fn serves bundled namespaces from.
;; Only the files listed in `shipped` are copied; anything else in the jars
;; is reported, so an upgrade shows every upstream addition for a decision.
(require '[babashka.fs :as fs]
         '[clojure.set :as set]
         '[clojure.string :as str])

(def m2 (str (fs/expand-home "~/.m2/repository/org/clojure")))

(def jars
  [(str m2 "/tools.deps/0.31.1638/tools.deps-0.31.1638.jar")
   (str m2 "/tools.deps.edn/0.9.42/tools.deps.edn-0.9.42.jar")
   (str m2 "/tools.gitlibs/2.6.217/tools.gitlibs-2.6.217.jar")])

;; Upstream files shipped verbatim.
(def shipped
  #{"clojure/tools/deps.clj"
    "clojure/tools/deps/deps.edn" ; root deps.edn, embedded into edn.clj by babashka.impl.tools-deps at build time
    "clojure/tools/deps/edn.clj"
    "clojure/tools/deps/extensions.clj"
    "clojure/tools/deps/extensions/deps.clj"
    "clojure/tools/deps/extensions/git.clj"
    "clojure/tools/deps/script/make_classpath2.clj"
    "clojure/tools/deps/script/parse.clj"
    "clojure/tools/deps/script/resolve_tags.clj"
    "clojure/tools/deps/tool.clj"
    "clojure/tools/deps/tree.clj"
    "clojure/tools/deps/util/concurrent.clj"
    "clojure/tools/deps/util/dir.clj"
    "clojure/tools/deps/util/io.clj"
    "clojure/tools/deps/util/session.clj"
    "clojure/tools/gitlibs.clj"
    "clojure/tools/gitlibs/config.clj"
    "clojure/tools/gitlibs/impl.clj"})

;; Upstream files bb replaces at the same path: the Maven-backed namespaces
;; have babashka.mvn stand-ins, local.clj is a patched copy. Never copied.
(def stand-ins
  #{"clojure/tools/deps/extensions/local.clj"
    "clojure/tools/deps/extensions/maven.clj"
    "clojure/tools/deps/extensions/pom.clj"
    "clojure/tools/deps/util/maven.clj"})

;; Upstream files bb does not ship: specs is a built-in stub, the rest is
;; not needed for make-classpath2.
(def dropped
  #{"clojure/tools/deps/specs.clj"
    "clojure/tools/deps/gen/pom.clj"
    "clojure/tools/deps/script/generate_manifest2.clj"
    "clojure/tools/deps/license-abbrev.edn"}) ; read by nothing shipped

(def target "resources/src/babashka")

(let [tmp (fs/create-temp-dir)]
  (doseq [jar jars]
    (fs/unzip jar tmp {:replace-existing true}))
  (let [upstream (->> (fs/glob tmp "clojure/tools/**")
                      (filter fs/regular-file?)
                      (map #(str (fs/relativize tmp %)))
                      (remove #(str/ends-with? % ".class"))
                      set)
        missing (remove upstream (concat shipped stand-ins dropped))
        new (sort (remove (set/union shipped stand-ins dropped) upstream))]
    (doseq [rel (sort shipped)]
      (fs/create-dirs (fs/parent (fs/file target rel)))
      (fs/copy (fs/file tmp rel) (fs/file target rel) {:replace-existing true})
      (println rel))
    (when (seq new)
      (println "\nUpstream files not shipped, decide per file:")
      (run! #(println " " %) new))
    (when (seq missing)
      (println "\nListed here but gone upstream:")
      (run! #(println " " %) missing))
    (fs/delete-tree tmp)
    (when (seq missing) (System/exit 1))))
