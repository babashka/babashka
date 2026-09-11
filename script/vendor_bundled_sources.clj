#!/usr/bin/env bb
;; Copy the sources babashka bundles into resources/src/babashka, where bb's
;; load-fn serves them from: tools.deps, tools.deps.edn and tools.gitlibs,
;; which resolve deps in-process, tools.build with the parts of
;; tools.namespace and java.classpath its compile-clj needs, and nREPL,
;; whose Java classes go to src-java.
;; Only the files listed in `shipped` are copied; anything else in the jars
;; is reported, so an upgrade shows every upstream addition for a decision.
;; Patches to shipped files are written here between BB-PATCH markers with
;; the upstream form kept under #_. The tools.deps version also goes into
;; the procurer's User-Agent, the nREPL version into nrepl.version.
(require '[babashka.fs :as fs]
         '[clojure.set :as set]
         '[clojure.string :as str]
         '[rewrite-clj.zip :as z])

(def m2 (str (fs/expand-home "~/.m2/repository")))

(def tools-deps-version "0.31.1638")
(def tools-deps-edn-version "0.9.42")
(def nrepl-version "1.7.0")
(def orchard-version "0.44.0")

(defn- jar [group artifact version]
  (str m2 "/" (str/replace group "." "/") "/" artifact "/" version "/"
       artifact "-" version ".jar"))

(def jars
  [(jar "org.clojure" "tools.deps" tools-deps-version)
   (jar "org.clojure" "tools.deps.edn" tools-deps-edn-version)
   (jar "org.clojure" "tools.gitlibs" "2.6.217")
   (jar "io.github.clojure" "tools.build" "0.10.14")
   (jar "org.clojure" "tools.namespace" "1.5.1")
   (jar "org.clojure" "java.classpath" "1.1.1")
   (jar "nrepl" "nrepl" nrepl-version)
   (jar "cider" "orchard" orchard-version)])

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

(defn- patch
  "`source` with the form `upstream` replaced by `ours`, kept under #_ and
  marked BB-PATCH with `why`. `upstream` must be one complete form, so the
  discard covers exactly it, and occur `n` times (default 1)."
  ([source upstream ours why] (patch source upstream ours why 1))
  ([source upstream ours why n]
   (let [found (dec (count (str/split source (re-pattern (java.util.regex.Pattern/quote upstream)) -1)))]
     (assert (= n found) (str "expected " n " occurrences, found " found ": " upstream)))
   (str/replace source upstream (str "#_" upstream " ;; BB-PATCH " why "\n" ours))))

(defn- subst
  "`source` with `from` replaced by `to`, `n` times (default 1). For renames
  inside a form, where a discarded upstream copy has no place: a protocol
  imported as a class, interop on a protocol instance."
  ([source from to] (subst source from to 1))
  ([source from to n]
   (let [found (dec (count (str/split source (re-pattern (java.util.regex.Pattern/quote from)) -1)))]
     (assert (= n found) (str "expected " n " occurrences, found " found ": " from)))
   (str/replace source from to)))

;; What sci cannot run as it is: Compiler internals, deftype and defrecord
;; over a Java interface, a private clojure.core var, a jar resource, and a
;; protocol imported as if it were a Java interface.
(def nrepl-patches
  {"nrepl/middleware/caught.clj"
   (fn [s]
     (-> s
         (patch "(nrepl.transport Transport)" "" "a sci protocol is not a class")
         (subst "(reify Transport" "(reify transport/Transport")))
   "nrepl/middleware/print.clj"
   (fn [s]
     (-> s
         (patch "(nrepl.transport Transport)" "" "a sci protocol is not a class")
         (subst "(reify Transport" "(reify transport/Transport")
         (patch "@#'clojure.core/pr-on"
                "(fn [x w] (binding [*out* w] (pr x)))"
                "pr-on is private to clojure.core and absent in sci")))
   "nrepl/middleware/load_file.clj"
   (fn [s]
     (-> s
         (patch "(clojure.lang Compiler)" "" "the image has no Compiler")
         (patch "(nrepl.transport Transport)" "" "a sci protocol is not a class")
         (subst "^Transport transport]" "transport]")
         (subst "(reify Transport" "(reify nrepl.transport/Transport")
         (subst "(.recv transport" "(nrepl.transport/recv transport" 2)
         (subst "(.send transport" "(nrepl.transport/send transport" 2)
         (patch "(defn- per-file-bindings [msg]
  {Compiler/METHOD nil
   Compiler/LOCAL_ENV nil
   Compiler/LOOP_LOCALS nil
   Compiler/NEXT_LOCAL_NUM 0
   ;; We don't set LINE_BEFORE, COLUMN_BEFORE, LINE_AFTER, COLUMN_AFTER because
   ;; it looks like it doesn't change much for our usecase. But this is still to
   ;; be confirmed.
   #'*read-eval* true
   ;; This function runs in \"server context\" (not yet in session context), so
   ;; make sure to resolve dynvar variables from session.
   #'*ns* (resolve-in-session msg *ns*)
   #'*unchecked-math* (resolve-in-session msg *unchecked-math*)
   #'*warn-on-reflection* (resolve-in-session msg *warn-on-reflection*)
   #'*data-readers* (resolve-in-session msg *data-readers*)})"
                "(defn- per-file-bindings [msg]
  {#'*read-eval* true
   #'*ns* (resolve-in-session msg *ns*)
   #'*unchecked-math* (resolve-in-session msg *unchecked-math*)
   #'*warn-on-reflection* (resolve-in-session msg *warn-on-reflection*)
   #'*data-readers* (resolve-in-session msg *data-readers*)})"
                "sci has no compiler state to reset per file")))
   "nrepl/middleware/interruptible_eval.clj"
   (fn [s]
     (-> s
         (patch "(clojure.lang Compiler$CompilerException
                 LineNumberingPushbackReader LispReader$ReaderException)"
                "(clojure.lang LineNumberingPushbackReader)"
                "the image has no Compiler or LispReader")
         (patch "(java.lang.reflect Field)" "" "no reflection on the reader's column field")
         (patch "(defn- set-column!
  [^LineNumberingPushbackReader reader column]
  (when-let [field (->> LineNumberingPushbackReader
                        (.getDeclaredFields)
                        (filter #(= \"_columnNumber\" (.getName ^Field %)))
                        first)]
    (-> ^Field field
        (doto (.setAccessible true))
        (.set reader column))))"
                "(defn- set-column! [_reader _column] nil)"
                "the column field is set through reflection")
         (patch "(or (instance? ThreadDeath (clojure.main/root-cause e))
      (and (instance? Compiler$CompilerException e)
           (instance? ThreadDeath (.getCause e))))"
                "(instance? ThreadDeath (clojure.main/root-cause e))"
                "no CompilerException in sci")
         (patch "{Compiler/SOURCE_PATH file
                                      Compiler/SOURCE file-name}"
                "{#'*file* file}"
                "sci reads the source path from *file*")
         (patch "(instance? LispReader$ReaderException e)"
                "(= :sci.error/parse (:type (ex-data e)))"
                "sci reader errors are ex-info")
         (patch "(Compiler/eval input true)" "(clojure.core/eval input)" "sci eval, eval is shadowed by the message key")))
   "nrepl/middleware/session.clj"
   (fn [s]
     (-> s
         (patch "(clojure.lang Compiler LineNumberingPushbackReader)"
                "(clojure.lang LineNumberingPushbackReader)"
                "the image has no Compiler")
         (patch "(defn- add-per-message-bindings
  \"Add dynamic bindings to `bindings-map` that must be rebound for each message.\"
  [{:keys [session file out-limit] :as msg} bindings-map]
  (let [;; *out* and *err* must be rebound on each new message.
        ;; TODO: out-limit -> out-buffer-size | err-buffer-size
        ;; TODO: new options: out-quota | err-quota
        opts {::print/buffer-size (or out-limit (get (meta session) :out-limit))}
        out (print/replying-PrintWriter :out msg opts)
        err (print/replying-PrintWriter :err msg opts)]
    (-> bindings-map
        (assoc #'*msg* msg
               Compiler/LOADER (classloader/dynamic-classloader)
               #'*out* out
               #'*err* err
               ;; clojure.test captures *out* at load-time, so we need to make
               ;; sure runtime output of test status/results is redirected
               ;; properly. There might be more cases like this, but we can't do
               ;; much about it besides patching like this. We intentionally
               ;; don't require beforehand in order to not add to loading times.
               (resolve 'clojure.test/*test-out*) out)
        (cond->
         file (assoc #'*file* file)))))"
                "(defn- add-per-message-bindings
  \"Add dynamic bindings to `bindings-map` that must be rebound for each message.\"
  [{:keys [session file out-limit] :as msg} bindings-map]
  (let [opts {::print/buffer-size (or out-limit (get (meta session) :out-limit))}
        out (print/replying-PrintWriter :out msg opts)
        err (print/replying-PrintWriter :err msg opts)]
    (-> bindings-map
        (assoc #'*msg* msg
               #'*out* out
               #'*err* err
               (resolve 'clojure.test/*test-out*) out)
        (cond->
         file (assoc #'*file* file)))))"
                "no classloader binding in sci")
         (patch "(dissoc #'*msg* Compiler/LOADER)" "(dissoc #'*msg*)" "no classloader binding in sci")))
   "nrepl/socket.clj"
   (fn [s]
     (-> s
         (patch "(defrecord BufferedOutputChannel
           [^SocketChannel channel ^ByteBuffer buffer]

  java.io.Flushable
  (flush [_this] ;; Underscore was added to satisfy clj-kondo
    (.flip buffer)
    (.write channel buffer)
    (.clear buffer))

  Writable
  (write [this byte-array]
    (.write this byte-array 0 (count byte-array)))
  (write [this byte-array offset length]
    (if (> length (.capacity buffer))
      (do
        (.flush this)
        (.write channel (ByteBuffer/wrap byte-array offset length)))
      (do
        (when (> length (.remaining buffer))
          (.flush this))
        (.put buffer byte-array offset length)))))"
                "(defn buffered-output-channel [^SocketChannel channel bytes]
  (assert (.isBlocking channel))
  (let [^ByteBuffer buffer (ByteBuffer/allocate bytes)
        flush! (fn []
                 (.flip buffer)
                 (.write channel buffer)
                 (.clear buffer))]
    (reify
      java.io.Flushable
      (flush [_this] (flush!))
      Writable
      (write [this byte-array]
        (write this byte-array 0 (count byte-array)))
      (write [_this byte-array offset length]
        (if (> length (.capacity buffer))
          (do (flush!)
              (.write channel (ByteBuffer/wrap byte-array offset length)))
          (do (when (> length (.remaining buffer))
                (flush!))
              (.put buffer byte-array offset length)))))))"
                "sci defrecord cannot implement a Java interface, reify can")
         (patch "(defn buffered-output-channel [^SocketChannel channel bytes]
  (assert (.isBlocking channel))
  (->BufferedOutputChannel channel (ByteBuffer/allocate bytes)))"
                ""
                "replaced above")))
   "nrepl/server.clj"
   (fn [s]
     (-> s
         (patch "(close [this] (stop-server this))" ""
                "sci defrecord cannot implement a Java interface, use stop-server")
         (subst "  java.io.Closeable\n  #_(close" "  #_(close")))
   "nrepl/transport.clj"
   (fn [s]
     (-> s
         (patch "(deftype FnTransport [recv-fn send-fn close]
  Transport
  (send [this msg] (send-fn msg) this)
  (recv [this] (.recv this Long/MAX_VALUE))
  (recv [_this timeout] (recv-fn timeout))
  java.io.Closeable
  (close [_this] (close)))"
                "(defn- fn-transport* [recv-fn send-fn close]
  (reify
    Transport
    (send [this msg] (send-fn msg) this)
    (recv [this] (recv this Long/MAX_VALUE))
    (recv [_this timeout] (recv-fn timeout))
    java.io.Closeable
    (close [_this] (close))))"
                "sci deftype cannot implement a Java interface, reify can")
         (subst "(FnTransport.\n" "(fn-transport*\n")))
   "orchard/print.clj"
   (fn [s]
     (-> s
         (patch "(clojure.lang AFunction Compiler IDeref IPending IPersistentMap MultiFn
                 IPersistentSet IPersistentVector IRecord Keyword Namespace
                 RT Symbol TaggedLiteral Var)"
                "(clojure.lang AFunction IDeref IPending IPersistentMap MultiFn
                 IPersistentSet IPersistentVector IRecord Keyword
                 RT Symbol TaggedLiteral)
   (sci.lang Namespace Var)"
                "no Compiler in the image; sci's vars and namespaces are its own types")
         (patch "(Compiler/demunge (.getName (class x)))"
                "(clojure.main/demunge (.getName (class x)))"
                "no Compiler in the image")
         (patch "(defmethod print :record [x, ^Writer w]
  (.write w \"#\")
  (.write w (if *short-record-names*
              (.getSimpleName (class x))
              (.getName (class x))))
  (print-map x w))"
                "(defmethod print :record [x, ^Writer w]
  (.write w \"#\")
  (.write w (let [full-name (.getName (type x))]
              (if *short-record-names*
                (subs full-name (inc (.lastIndexOf full-name \".\")))
                full-name)))
  (print-map x w))"
                "every sci record is a SciRecord, its name is on the sci type")
         ;; interop on Clojure values needs reflection registration in the
         ;; image, the core functions do not
         (subst "(.write w (.toString x))" "(.write w (str x))")
         (subst "(.toString kw)" "(str kw)" 2)
         (subst "(not (.isRealized ^IPending x))" "(not (realized? x))")))
   "orchard/pp.clj"
   (fn [s]
     ;; every sci record is a SciRecord, its name is on the sci type
     (-> s
         (subst "(.getSimpleName (class coll))"
                "(let [n (.getName (type coll))] (subs n (inc (.lastIndexOf n \".\"))))")
         (subst "(.getName (class coll))" "(.getName (type coll))")))
   "orchard/java/compatibility.clj"
   (fn [s]
     (patch s "(catch Exception ~'_ ::access-denied)"
            "(catch Throwable ~'_ ::access-denied)"
            "an unregistered field throws MissingReflectionRegistrationError in the image" 2))
   "orchard/inspect/analytics.clj"
   (fn [s]
     (-> s
         (patch "(definline ^:private inc-if [val condition]
  `(cond-> ~val ~condition inc))"
                "(defn- inc-if [val condition] (cond-> val condition inc))"
                "sci has no definline")
         (subst "(.iterator coll)" "(RT/iter coll)" 3)))
   "orchard/inspect.clj"
   (fn [s]
     (-> s
         (subst "(if-not (.isBound obj)" "(if-not (bound? obj)")
         (patch "(#'clojure.reflect/parse-flags (.getModifiers obj) :class)"
            "(let [m (.getModifiers obj)]
                                 (remove nil? [(when (Modifier/isPublic m) :public)
                                               (when (Modifier/isAbstract m) :abstract)
                                               (when (Modifier/isFinal m) :final)
                                               (when (Modifier/isStatic m) :static)]))"
            "clojure.reflect's private parse-flags is not reachable in sci" 2)))
   "nrepl/version.clj"
   (fn [s]
     (patch s "(get-version \"nrepl\" \"nrepl\")"
            (str "\"" nrepl-version "\"")
            "the pom.properties resource is not in the image"))})

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
  (let [upstream (->> (concat (fs/glob tmp "clojure/**") (fs/glob tmp "nrepl/**") (fs/glob tmp "orchard/**") (fs/glob tmp "mx/**"))
                      (filter fs/regular-file?)
                      (map #(str (fs/relativize tmp %)))
                      (remove #(or (str/ends-with? % ".class") (str/ends-with? % ".so")))
                      set)
        missing (remove upstream (concat shipped java-shipped stand-ins dropped))
        new (sort (remove (set/union shipped java-shipped stand-ins dropped) upstream))
        root-deps-edn (read-string (slurp (fs/file tmp "clojure/tools/deps/deps.edn")))]
    (doseq [rel (sort shipped)]
      (fs/create-dirs (fs/parent (fs/file target rel)))
      (cond (= "clojure/tools/deps/edn.clj" rel)
            (spit (fs/file target rel) (patch-root-deps (slurp (fs/file tmp rel)) root-deps-edn))
            (nrepl-patches rel)
            (spit (fs/file target rel) ((nrepl-patches rel) (slurp (fs/file tmp rel))))
            :else
            (fs/copy (fs/file tmp rel) (fs/file target rel) {:replace-existing true}))
      (println rel))
    (doseq [rel (sort java-shipped)]
      (fs/create-dirs (fs/parent (fs/file java-target rel)))
      (fs/copy (fs/file tmp rel) (fs/file java-target rel) {:replace-existing true})
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
