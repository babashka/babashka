#!/usr/bin/env bb
;; Copy the sources babashka bundles into resources/src/babashka, where bb's
;; load-fn serves them from: tools.deps, tools.deps.edn and tools.gitlibs,
;; which resolve deps in-process, and tools.build with the parts of
;; tools.namespace and java.classpath its compile-clj needs.
;; Only the files listed in `shipped` are copied; anything else in the jars
;; is reported, so an upgrade shows every upstream addition for a decision.
;; The one patch to a shipped file, root-deps in edn.clj, is written here
;; between BB-PATCH markers with the upstream form kept under #_. The
;; tools.deps version also goes into the procurer's User-Agent.
(require '[babashka.fs :as fs]
         '[clojure.set :as set]
         '[clojure.string :as str]
         '[rewrite-clj.zip :as z])

(def m2 (str (fs/expand-home "~/.m2/repository")))

(def tools-deps-version "0.31.1638")
(def tools-deps-edn-version "0.9.42")

(defn- jar [group artifact version]
  (str m2 "/" (str/replace group "." "/") "/" artifact "/" version "/"
       artifact "-" version ".jar"))

(def jars
  [(jar "org.clojure" "tools.deps" tools-deps-version)
   (jar "org.clojure" "tools.deps.edn" tools-deps-edn-version)
   (jar "org.clojure" "tools.gitlibs" "2.6.217")
   (jar "io.github.clojure" "tools.build" "0.10.14")
   (jar "org.clojure" "tools.namespace" "1.5.1")
   (jar "org.clojure" "java.classpath" "1.1.1")])

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
    "clojure/tools/gitlibs/impl.clj"
    "clojure/tools/build/api.clj"
    "clojure/tools/build/api/specs.clj"
    "clojure/tools/build/tasks/compile_clj.clj"
    "clojure/tools/build/tasks/copy.clj"
    "clojure/tools/build/tasks/create_basis.clj"
    "clojure/tools/build/tasks/jar.clj"
    "clojure/tools/build/tasks/process.clj"
    "clojure/tools/build/tasks/uber.clj"
    "clojure/tools/build/tasks/write_pom.clj"
    "clojure/tools/build/tasks/zip.clj"
    "clojure/tools/build/util/file.clj"
    "clojure/tools/build/util/log.clj"
    "clojure/tools/build/util/zip.clj"
    "clojure/tools/namespace/dependency.cljc"
    "clojure/tools/namespace/file.clj"
    "clojure/tools/namespace/find.clj"
    "clojure/tools/namespace/parse.cljc"
    "clojure/tools/namespace/track.cljc"
    "clojure/java/classpath.clj"})

;; Upstream files bb replaces at the same path: the Maven-backed namespaces
;; have babashka.impl.mvn stand-ins, local.clj is a patched copy, and two
;; tools.build tasks use what the image lacks. Never copied.
(def stand-ins
  #{"clojure/tools/deps/extensions/local.clj"
    "clojure/tools/deps/extensions/maven.clj"
    "clojure/tools/deps/extensions/pom.clj"
    "clojure/tools/deps/util/maven.clj"
    "clojure/tools/build/tasks/install.clj"
    "clojure/tools/build/tasks/javac.clj"})

;; Upstream files bb does not ship: specs is a built-in stub, deps.edn is
;; embedded into edn.clj below, the rest is not needed by what is shipped.
(def dropped
  #{"clojure/tools/deps/specs.clj"
    "clojure/tools/deps/deps.edn"
    "clojure/tools/deps/gen/pom.clj"
    "clojure/tools/deps/script/generate_manifest2.clj"
    "clojure/tools/deps/license-abbrev.edn" ; read by nothing shipped
    "clojure/tools/namespace.clj"
    "clojure/tools/namespace/dir.clj"
    "clojure/tools/namespace/move.clj"
    "clojure/tools/namespace/reload.clj"
    "clojure/tools/namespace/repl.clj"})

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
                    ", embedded by script/vendor_bundled_sources.clj.\"\n"
                    "  []\n"
                    "  '" (pr-str root-deps-edn) ")"))
        block (str ";; BB-PATCH the root deps.edn is a jar resource the image cannot see\n"
                   "#_" upstream "\n\n"
                   ours "\n"
                   ";; END-BB-PATCH")]
    (str/replace-first source upstream block)))

(when-let [absent (seq (remove fs/exists? jars))]
  (println "Not in ~/.m2, resolve them first:")
  (run! #(println " " %) absent)
  (System/exit 1))

(let [tmp (fs/create-temp-dir)]
  (doseq [jar jars]
    (fs/unzip jar tmp {:replace-existing true}))
  (let [upstream (->> (fs/glob tmp "clojure/**")
                      (filter fs/regular-file?)
                      (map #(str (fs/relativize tmp %)))
                      (remove #(str/ends-with? % ".class"))
                      set)
        missing (remove upstream (concat shipped stand-ins dropped))
        new (sort (remove (set/union shipped stand-ins dropped) upstream))
        root-deps-edn (read-string (slurp (fs/file tmp "clojure/tools/deps/deps.edn")))]
    (doseq [rel (sort shipped)]
      (fs/create-dirs (fs/parent (fs/file target rel)))
      (if (= "clojure/tools/deps/edn.clj" rel)
        (spit (fs/file target rel) (patch-root-deps (slurp (fs/file tmp rel)) root-deps-edn))
        (fs/copy (fs/file tmp rel) (fs/file target rel) {:replace-existing true}))
      (println rel))
    (let [http "resources/src/babashka/babashka/impl/mvn/http.clj"
          source (slurp http)
          re #"\(def \^:private tools-deps-version \"[^\"]+\"\)"]
      (assert (re-find re source) "tools-deps-version not found in http.clj")
      (spit http (str/replace source re (str "(def ^:private tools-deps-version \"" tools-deps-version "\")"))))
    (when (seq new)
      (println "\nUpstream files not shipped, decide per file:")
      (run! #(println " " %) new))
    (when (seq missing)
      (println "\nListed here but gone upstream:")
      (run! #(println " " %) missing))
    (fs/delete-tree tmp)
    (when (seq missing) (System/exit 1))))
