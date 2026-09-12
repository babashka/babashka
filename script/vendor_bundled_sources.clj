#!/usr/bin/env bb
;; Refresh the sources babashka bundles in resources/src/babashka, where bb's
;; load-fn serves them from, and nREPL's Java classes in src-java.
;;
;; Those files are the source of truth, not build output: babashka's changes
;; live in them, each marked BB-PATCH with the upstream form kept under #_.
;; This script only brings upstream's changes in, by three-way merging every
;; shipped file against the version it currently holds, which script/vendored.edn
;; records. Files we never touched come across wholesale; files we did keep our
;; changes, and a collision leaves conflict markers to resolve by hand.
;;
;; Usage:
;;   bb script/vendor_bundled_sources.clj                    ; refresh at the current pins
;;   bb script/vendor_bundled_sources.clj nrepl/nrepl 1.8.0  ; bump one artifact
;;
;; Only the files listed in `shipped` are taken; anything else in the jars is
;; reported, so an upgrade shows every upstream addition for a decision.
(require '[babashka.fs :as fs]
         '[babashka.process :refer [shell]]
         '[clojure.edn :as edn]
         '[clojure.set :as set]
         '[clojure.string :as str])

(def m2 (str (fs/expand-home "~/.m2/repository")))

(def pins-file "script/vendored.edn")

(def pins
  "The versions the files in the tree came from."
  (edn/read-string (slurp pins-file)))

(def bumped
  "The versions to move to: the pins, with the command line applied."
  (let [[lib version] *command-line-args*]
    (if lib
      (let [lib (symbol lib)]
        (assert (contains? pins lib) (str "Not a vendored artifact: " lib))
        (assert version "Give a version to bump to")
        (assoc pins lib version))
      pins)))

(defn- jar [lib version]
  (str m2 "/" (str/replace (namespace lib) "." "/") "/" (name lib) "/" version "/"
       (name lib) "-" version ".jar"))

(defn- jars-for [versions]
  (mapv (fn [[lib version]] (jar lib version)) versions))

(def tools-deps-version (bumped 'org.clojure/tools.deps))
(def tools-deps-edn-version (bumped 'org.clojure/tools.deps.edn))
(def nrepl-version (bumped 'nrepl/nrepl))

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
    "clojure/java/classpath.clj"
    "nrepl/ack.clj"
    "nrepl/config.clj"
    "nrepl/core.clj"
    "nrepl/middleware.clj"
    "nrepl/middleware/caught.clj"
    "nrepl/middleware/completion.clj"
    "nrepl/middleware/interruptible_eval.clj"
    "nrepl/middleware/io.clj"
    "nrepl/middleware/load_file.clj"
    "nrepl/middleware/lookup.clj"
    "nrepl/middleware/print.clj"
    "nrepl/middleware/session.clj"
    "nrepl/misc.clj"
    "nrepl/server.clj"
    "nrepl/socket.clj"
    "nrepl/socket/dynamic.clj"
    "nrepl/transport.clj"
    "nrepl/util/lookup.clj"
    "nrepl/util/out.clj"
    "nrepl/util/print.clj"
    "nrepl/util/threading.clj"
    "nrepl/version.clj"
    ;; the CIDER inspector's rendering engine
    "orchard/inspect.clj"
    "orchard/inspect/analytics.clj"
    "orchard/java/compatibility.clj"
    "orchard/misc.clj"
    "orchard/pp.clj"
    "orchard/print.clj"
    "orchard/util/io.clj"})

;; nREPL's Java classes, compiled into bb from src-java.
(def java-shipped
  #{"nrepl/SessionThread.java"
    "nrepl/DaemonThreadFactory.java"
    "nrepl/in/QueuePollingReader.java"
    "nrepl/out/CallbackBufferedOutputStream.java"
    "nrepl/out/QuotaBoundWriter.java"
    "nrepl/out/QuotaExceeded.java"
    "nrepl/out/TeeOutputStream.java"
    "mx/cider/orchard/TruncatingStringWriter.java"})

;; Upstream files bb replaces at the same path: the Maven-backed namespaces
;; have babashka.impl.mvn stand-ins, local.clj is a patched copy, two
;; tools.build tasks use what the image lacks, and four nREPL namespaces:
;; bencode delegates to the compiled bencode.core, completion to bb's own,
;; the classloader is nil in an image, and TLS is not supported. Never copied.
(def stand-ins
  #{"clojure/tools/deps/extensions/local.clj"
    "clojure/tools/deps/extensions/maven.clj"
    "clojure/tools/deps/extensions/pom.clj"
    "clojure/tools/deps/util/maven.clj"
    "clojure/tools/build/tasks/install.clj"
    "clojure/tools/build/tasks/javac.clj"
    "nrepl/bencode.clj"
    "nrepl/tls.clj"
    "nrepl/util/classloader.clj"
    "nrepl/util/completion.clj"})

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
    "clojure/tools/namespace/repl.clj"
    "nrepl/cmdline.clj" ; bb has its own command line
    "nrepl/spec.clj"
    "nrepl/tls_client_proxy.clj"
    "nrepl/util/jvmti.clj" ; only reached with -Djdk.attach.allowAttachSelf
    "nrepl/JvmtiAgent.java"
    "nrepl/main.java"
    ;; orchard beyond the inspector: JVM introspection bb does not do
    "orchard/apropos.clj"
    "orchard/cljs/analysis.cljc"
    "orchard/cljs/meta.cljc"
    "orchard/clojuredocs.clj"
    "orchard/eldoc.clj"
    "orchard/indent.clj"
    "orchard/info.clj"
    "orchard/java.clj"
    "orchard/java/classpath.clj"
    "orchard/java/parser_next.clj"
    "orchard/java/resource.clj"
    "orchard/java/source_files.clj"
    "orchard/meta.clj"
    "orchard/namespace.clj"
    "orchard/profile.clj"
    "orchard/query.clj"
    "orchard/spec.clj"
    "orchard/stacktrace.clj"
    "orchard/trace.clj"
    "orchard/util/os.clj"
    "orchard/xref.clj"
    "mx/cider/orchard/LruMap.java"})

(def target "resources/src/babashka")
(def java-target "src-java")

(defn- extract
  "Unzips `jars` into a fresh directory and returns it."
  [jars]
  (let [dir (fs/create-temp-dir)]
    (doseq [jar jars]
      (fs/unzip jar dir {:replace-existing true}))
    dir))

(defn- merge-file!
  "Brings upstream's changes to `rel` into `dest`, keeping ours. Returns
  :added when we did not have the file, :clean, or the conflict count."
  [dest rel old new]
  (let [ours (fs/file dest rel)
        base (fs/file old rel)
        theirs (fs/file new rel)]
    (fs/create-dirs (fs/parent ours))
    (cond
      (not (fs/exists? ours))
      (do (fs/copy theirs ours {:replace-existing true}) :added)

      (not (fs/exists? base))
      (do (fs/copy theirs ours {:replace-existing true}) :added)

      :else
      (let [{:keys [exit]} (shell {:continue true :out :string :err :string}
                                  "git" "merge-file" (str ours) (str base) (str theirs))]
        (if (zero? exit) :clean exit)))))

(defn- stamp!
  "Replaces the value matched by `re` in `file` with `value`."
  [file re value what]
  (let [source (slurp file)]
    (assert (re-find re source) (str what " not found in " file))
    (spit file (str/replace source re value))))

(let [old-jars (jars-for pins)
      new-jars (jars-for bumped)]
  (when-let [absent (seq (remove fs/exists? (distinct (concat old-jars new-jars))))]
    (println "Not in ~/.m2, resolve them first:")
    (run! #(println " " %) absent)
    (System/exit 1))
  (let [old (extract old-jars)
        new (extract new-jars)
        upstream (->> (concat (fs/glob new "clojure/**") (fs/glob new "nrepl/**")
                              (fs/glob new "orchard/**") (fs/glob new "mx/**"))
                      (filter fs/regular-file?)
                      (map #(str (fs/relativize new %)))
                      (remove #(or (str/ends-with? % ".class") (str/ends-with? % ".so")))
                      set)
        missing (remove upstream (concat shipped java-shipped stand-ins dropped))
        added (sort (remove (set/union shipped java-shipped stand-ins dropped) upstream))
        results (concat
                 (for [rel (sort shipped)] [rel (merge-file! target rel old new)])
                 (for [rel (sort java-shipped)] [rel (merge-file! java-target rel old new)]))
        conflicted (remove (comp #{:clean} second) results)]
    (doseq [[rel status] results
            :when (not= :clean status)]
      (println (format "%-52s %s" rel (case status
                                        :added "new here, taken from upstream"
                                        (str status " conflict(s)")))))
    ;; versions babashka embeds rather than reads at run time
    (stamp! "resources/src/babashka/babashka/impl/mvn/http.clj"
            #"\(def \^:private tools-deps-version \"[^\"]+\"\)"
            (str "(def ^:private tools-deps-version \"" tools-deps-version "\")")
            "tools-deps-version")
    (stamp! (str (fs/file target "nrepl/version.clj"))
            #"(;; BB-PATCH the pom.properties resource is not in the image\n)\"[^\"]+\""
            (str "$1\"" nrepl-version "\"")
            "nrepl version literal")
    ;; the root deps.edn is embedded in edn.clj, so it must match the jar we pin
    (let [embedded (let [src (slurp (str (fs/file target "clojure/tools/deps/edn.clj")))
                         i (str/last-index-of src "(defn root-deps")
                         body (last (read-string (subs src i)))]
                     (if (and (seq? body) (= 'quote (first body))) (second body) body))
          upstream-deps (read-string (slurp (str (fs/file new "clojure/tools/deps/deps.edn"))))]
      (when-not (= embedded upstream-deps)
        (println "\nThe root deps.edn embedded in clojure/tools/deps/edn.clj is stale.")
        (println "Replace the map in its root-deps with the one from tools.deps.edn"
                 (bumped 'org.clojure/tools.deps.edn))
        (System/exit 1)))
    (when (seq added)
      (println "\nUpstream files not shipped, decide per file:")
      (run! #(println " " %) added))
    (when (seq missing)
      (println "\nListed here but gone upstream:")
      (run! #(println " " %) missing))
    (when (not= pins bumped)
      (spit pins-file (str/replace (slurp pins-file)
                                   (re-pattern (str "(?m)^(\\s*" (java.util.regex.Pattern/quote (str (first (first (remove (fn [[k v]] (= v (pins k))) bumped))))) "\\s+)\"[^\"]+\"" ))
                                   (str "$1\"" (second (first (remove (fn [[k v]] (= v (pins k))) bumped))) "\"")))
      (println "\nscript/vendored.edn updated"))
    (fs/delete-tree old)
    (fs/delete-tree new)
    (when (seq conflicted)
      (println "\nResolve the conflict markers before committing."))
    (when (seq missing) (System/exit 1))))
