#!/usr/bin/env bb
;; Copy the tools.deps, tools.deps.edn and tools.gitlibs sources into
;; resources/src/babashka, where bb's load-fn serves bundled namespaces from.
;; Only the files listed in `shipped` are copied; anything else in the jars
;; is reported, so an upgrade shows every upstream addition for a decision.
;; The one patch to a shipped file, root-deps in edn.clj, is written here
;; between BB-PATCH markers with the upstream form kept under #_.
(require '[babashka.fs :as fs]
         '[clojure.set :as set]
         '[clojure.string :as str]
         '[rewrite-clj.zip :as z])

(def m2 (str (fs/expand-home "~/.m2/repository/org/clojure")))

(def tools-deps-edn-version "0.9.42")

(def jars
  [(str m2 "/tools.deps/0.31.1638/tools.deps-0.31.1638.jar")
   (str m2 "/tools.deps.edn/" tools-deps-edn-version "/tools.deps.edn-" tools-deps-edn-version ".jar")
   (str m2 "/tools.gitlibs/2.6.217/tools.gitlibs-2.6.217.jar")])

;; Upstream files shipped verbatim, edn.clj with the patch below.
(def shipped
  #{"clojure/tools/deps.clj"
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
;; have babashka.impl.mvn stand-ins, local.clj is a patched copy. Never copied.
(def stand-ins
  #{"clojure/tools/deps/extensions/local.clj"
    "clojure/tools/deps/extensions/maven.clj"
    "clojure/tools/deps/extensions/pom.clj"
    "clojure/tools/deps/util/maven.clj"})

;; Upstream files bb does not ship: specs is a built-in stub, deps.edn is
;; embedded into edn.clj below, the rest is not needed for make-classpath2.
(def dropped
  #{"clojure/tools/deps/specs.clj"
    "clojure/tools/deps/deps.edn"
    "clojure/tools/deps/gen/pom.clj"
    "clojure/tools/deps/script/generate_manifest2.clj"
    "clojure/tools/deps/license-abbrev.edn"}) ; read by nothing shipped

(def target "resources/src/babashka")

(defn patch-root-deps
  "edn.clj with root-deps replaced: upstream reads the root deps.edn as a
  jar resource, which the image cannot see, so the data is embedded."
  [source root-deps-edn]
  (let [zloc (-> (z/of-string source)
                 (z/find-value z/next 'root-deps)
                 z/up)
        upstream (z/string zloc)
        _ (assert (str/starts-with? upstream "(defn root-deps") upstream)
        ours (binding [*print-namespace-maps* false]
               (str "(defn root-deps\n"
                    "  \"The root deps.edn of tools.deps.edn " tools-deps-edn-version
                    ", embedded by script/vendor_tools_deps.clj.\"\n"
                    "  []\n"
                    "  '" (pr-str root-deps-edn) ")"))
        block (str ";; BB-PATCH the root deps.edn is a jar resource the image cannot see\n"
                   "#_" upstream "\n\n"
                   ours "\n"
                   ";; END-BB-PATCH")]
    (str/replace-first source upstream block)))

(let [tmp (fs/create-temp-dir)]
  (doseq [jar jars]
    (fs/unzip jar tmp {:replace-existing true}))
  (let [upstream (->> (fs/glob tmp "clojure/tools/**")
                      (filter fs/regular-file?)
                      (map #(str (fs/relativize tmp %)))
                      (remove #(str/ends-with? % ".class"))
                      set)
        missing (remove upstream (concat shipped stand-ins dropped))
        new (sort (remove (set/union shipped stand-ins dropped) upstream))
        root-deps-edn (read-string (slurp (fs/file tmp "clojure/tools/deps/deps.edn")))]
    (doseq [rel (sort shipped)]
      (fs/create-dirs (fs/parent (fs/file target rel)))
      (if (= rel "clojure/tools/deps/edn.clj")
        (spit (fs/file target rel) (patch-root-deps (slurp (fs/file tmp rel)) root-deps-edn))
        (fs/copy (fs/file tmp rel) (fs/file target rel) {:replace-existing true}))
      (println rel))
    (when (seq new)
      (println "\nUpstream files not shipped, decide per file:")
      (run! #(println " " %) new))
    (when (seq missing)
      (println "\nListed here but gone upstream:")
      (run! #(println " " %) missing))
    (fs/delete-tree tmp)
    (when (seq missing) (System/exit 1))))
