(ns babashka.main
  {:no-doc true}
  (:refer-clojure :exclude [error-handler])
  (:require
   [aaaa-this-has-to-be-first.because-patches]
   [babashka.deps :as bdeps]
   [babashka.fs :as fs]
   [babashka.impl.bencode :refer [bencode-namespace]]
   [babashka.impl.cheshire :refer [cheshire-core-namespace cheshire-factory-namespace]]
   [babashka.impl.classes :as classes :refer [classes-namespace]]
   [babashka.impl.classpath :as cp :refer [classpath-namespace]]
   [babashka.impl.cli :as cli]
   [babashka.impl.clojure.core :as core :refer [core-extras]]
   [babashka.impl.clojure.core.async :refer [async-namespace
                                             async-protocols-namespace]]
   [babashka.impl.clojure.instant :as instant]
   [babashka.impl.clojure.java.browse :refer [browse-namespace]]
   [babashka.impl.clojure.java.io :refer [io-namespace]]
   [babashka.impl.clojure.java.process :refer [cjp-namespace]]
   [babashka.impl.clojure.java.shell :refer [shell-namespace]]
   [babashka.impl.clojure.main :as clojure-main :refer [demunge]]
   [babashka.impl.clojure.math :refer [math-namespace]]
   [babashka.impl.clojure.reflect :refer [reflect-namespace]]
   [babashka.impl.clojure.stacktrace :refer [stacktrace-namespace]]
   [babashka.impl.clojure.tools.reader :refer [reader-namespace]]
   [babashka.impl.clojure.tools.reader-types :refer [edn-namespace
                                                     reader-types-namespace]]
   [babashka.impl.clojure.zip :refer [zip-namespace]]
   [babashka.impl.common :as common]
   [babashka.impl.core :as bbcore]
   [babashka.impl.curl :refer [curl-namespace]]
   [babashka.impl.data :as data]
   [babashka.impl.datafy :refer [datafy-namespace]]
   [babashka.impl.deps :as deps :refer [deps-namespace]]
   [babashka.impl.edamame :refer [edamame-namespace]]
   [babashka.impl.error-handler :refer [error-handler]]
   [babashka.impl.features :as features]
   [babashka.impl.fs :refer [fs-namespace]]
   [babashka.impl.http-client :refer [http-client-namespace
                                      http-client-websocket-namespace
                                      http-client-interceptors-namespace]]
   [babashka.impl.markdown :as md]
   [babashka.impl.nrepl-server :refer [nrepl-server-namespace]]
   [babashka.impl.pods :as pods]
   [babashka.impl.pprint :refer [pprint-namespace]]
   [babashka.impl.print-deps :as print-deps]
   [babashka.impl.process :refer [process-namespace]]
   [babashka.impl.protocols :refer [protocols-namespace]]
   [babashka.impl.proxy :refer [proxy-fn]]
   [babashka.impl.reify2 :refer [reify-fn]]
   [babashka.impl.repl :as repl]
   [babashka.impl.rewrite-clj :as rewrite]
   [babashka.impl.sci :refer [sci-core-namespace]]
   [babashka.impl.server :refer [clojure-core-server-namespace]]
   [babashka.impl.socket-repl :as socket-repl]
   [babashka.impl.tasks :as tasks :refer [tasks-namespace]]
   [babashka.impl.test :as t]
   [babashka.impl.tools.cli :refer [tools-cli-namespace]]
   [babashka.impl.uberscript :as uberscript]
   [babashka.nrepl.server :as nrepl-server]
   [babashka.wait :refer [wait-namespace]]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [edamame.core :as edamame]
   [hf.depstar.uberjar :as uberjar]
   [sci.addons :as addons]
   [sci.core :as sci]
   [sci.ctx-store :as ctx-store]
   [sci.impl.copy-vars :as sci-copy-vars]
   [sci.impl.io :as sio]
   [sci.impl.namespaces :as sci-namespaces]
   [sci.impl.parser]
   [sci.impl.types :as sci-types]
   [sci.impl.unrestrict :refer [*unrestricted*]]
   [sci.impl.vars :as vars])
  (:gen-class))

(def windows?
  (some-> (System/getProperty "os.name")
          (str/lower-case)
          (str/index-of "win")))

(if-not windows?
  (do ;; see https://github.com/oracle/graal/issues/1784
    (require 'babashka.impl.pipe-signal-handler)
    (let [handle-pipe! (resolve 'babashka.impl.pipe-signal-handler/handle-pipe!)]
      (def handle-pipe! @handle-pipe!))
    (let [pipe-signal-received? (resolve 'babashka.impl.pipe-signal-handler/pipe-signal-received?)]
      (def pipe-signal-received? @pipe-signal-received?))
    ;; JVM_FindSignal called:  Unimplemented
    (require 'babashka.impl.sigint-handler)
    (def handle-sigint! @(resolve 'babashka.impl.sigint-handler/handle-sigint!)))
  (do
    (def handle-pipe! (constantly nil))
    (def pipe-signal-received? (constantly false))
    (def handle-sigint! (constantly nil))))

(def signal-ns {'pipe-signal-received? (sci/copy-var pipe-signal-received? (sci/create-ns 'babashka.signal nil))})

(sci/enable-unrestricted-access!)
(sci/alter-var-root sci/in (constantly *in*))
(sci/alter-var-root sci/out (constantly *out*))
(sci/alter-var-root sci/err (constantly *err*))
(sci/alter-var-root sci/read-eval (constantly *read-eval*))

(set! *warn-on-reflection* true)
;; To detect problems when generating the image, run:
;; echo '1' | java -agentlib:native-image-agent=config-output-dir=/tmp -jar target/babashka-xxx-standalone.jar '...'
;; with the java provided by GraalVM.

(def version common/version)

(def build-commit-sha (or (System/getenv "BABASHKA_SHA") ""))

(defn parse-version [version]
  (mapv #(Integer/parseInt %)
        (-> version
            (str/replace "-SNAPSHOT" "")
            (str/split #"\."))))
(def version-data (parse-version version))

(defn print-version []
  (println (str "babashka v" version)))

(defn command? [x]
  (case x
    ("clojure"
     "version"
     "help"
     "doc"
     "tasks"
     "run"
     "uberjar"
     "uberscript"
     "repl"
     "socket-repl"
     "nrepl-server"
     "describe"
     "print-deps"
     "prepare") true
    false))

(defn print-error [& msgs]
  (binding [*out* *err*]
    (apply println msgs)))

(defn print-help []
  (println (str "Babashka v" version))
  (println "
Usage: bb [svm-opts] [global-opts] [eval opts] [cmdline args]
or:    bb [svm-opts] [global-opts] file [cmdline args]
or:    bb [svm-opts] [global-opts] task [cmdline args]
or:    bb [svm-opts] [global-opts] subcommand [subcommand opts] [cmdline args]

Substrate VM opts:

  -Xmx<size>[g|G|m|M|k|K]  Set a maximum heap size (e.g. -Xmx256M to limit the heap to 256MB).
  -XX:PrintFlags=          Print all Substrate VM options.

Global opts:

  -cp, --classpath  Classpath to use. Overrides bb.edn classpath.
  --debug           Print debug information and internal stacktrace in case of exception.
  --init <file>     Load file after any preloads and prior to evaluation/subcommands.
  --config <file>   Replace bb.edn with file. Defaults to bb.edn adjacent to invoked file or bb.edn in current dir. Relative paths are resolved relative to bb.edn.
  --deps-root <dir> Treat dir as root of relative paths in config.
  --prn             Print result via clojure.core/prn
  -Sforce           Force recalculation of the classpath (don't use the cache)
  -Sdeps            Deps data to use as the last deps file to be merged
  -f, --file <path> Run file
  --jar <path>      Run uberjar

Help:

  help, -h or -?     Print this help text.
  version            Print the current version of babashka.
  describe           Print an EDN map with information about this version of babashka.
  doc <var|ns>       Print docstring of var or namespace. Requires namespace if necessary.

Evaluation:

  -e, --eval <expr>    Evaluate an expression.
  -m, --main <ns|var>  Call the -main function from a namespace or call a fully qualified var.
  -x, --exec <var>     Call the fully qualified var. Args are parsed by babashka CLI.

REPL:

  repl                 Start REPL. Use rlwrap for history.
  socket-repl  [addr]  Start a socket REPL. Address defaults to localhost:1666.
  nrepl-server [addr]  Start nREPL server. Address defaults to localhost:1667.

Tasks:

  tasks       Print list of available tasks.
  run <task>  Run task. See run --help for more details.

Clojure:

  clojure [args...]  Invokes clojure. Takes same args as the official clojure CLI.

Packaging:

  uberscript <file> [eval-opt]  Collect all required namespaces from the classpath into a single file. Accepts additional eval opts, like `-m`.
  uberjar    <jar>  [eval-opt]  Similar to uberscript but creates jar file.
  prepare                       Download deps & pods defined in bb.edn and cache their metadata. Only an optimization, this will happen on demand when needed.

In- and output flags (only to be used with -e one-liners):

  -i                 Bind *input* to a lazy seq of lines from stdin.
  -I                 Bind *input* to a lazy seq of EDN values from stdin.
  -o                 Write lines to stdout.
  -O                 Write EDN values to stdout.
  --stream           Stream over lines or EDN values from stdin. Combined with -i or -I *input* becomes a single value per iteration.

Tooling:

  print-deps [--format <deps | classpath>]: prints a deps.edn map or classpath
    with built-in deps and deps from bb.edn.

File names take precedence over subcommand names.
Remaining arguments are bound to *command-line-args*.
Use -- to separate script command line args from bb command line args.
When no eval opts or subcommand is provided, the implicit subcommand is repl."))

(defn print-doc [ctx command-line-args]
  (let [arg (first command-line-args)
        tasks (:tasks @common/bb-edn)]
    (if (or (when-let [s (tasks/doc-from-task
                          ctx
                          tasks
                          (get tasks (symbol arg)))]
              [(do (println "-------------------------")
                   (println arg)
                   (println "Task")
                   (println s)
                   true) 0])
            (sci/eval-string* ctx (format "
(when (or (resolve '%1$s)
          (if (simple-symbol? '%1$s)
            (try (require '%1$s) true
              (catch Exception e nil))
            (try (requiring-resolve '%1$s) true
              (catch Exception e nil))))
 (clojure.repl/doc %1$s)
 true)" arg)))
      [nil 0]
      [nil 1])))

(defn print-run-help []
  (println (str/trim "
bb run [opts] <task>: run a task.

Supported options:

  --prn:      print task result using prn.
  --parallel: executes task dependencies in parallel when possible.

Use bb run --help to show this help output.
")))

(defn print-describe []
  (println
   (format
    (str/trim "
{:babashka/version   \"%s\"
 :git/sha            \"%s\"
 :feature/csv        %s
 :feature/java-nio   %s
 :feature/java-time  %s
 :feature/xml        %s
 :feature/yaml       %s
 :feature/jdbc       %s
 :feature/postgresql %s
 :feature/sqlite %s
 :feature/hsqldb     %s
 :feature/oracledb   %s
 :feature/httpkit-client %s
 :feature/lanterna %s
 :feature/core-match %s
 :feature/hiccup     %s
 :feature/test-check %s
 :feature/spec-alpha %s
 :feature/selmer %s
 :feature/logging %s
 :feature/priority-map %s}")
    version
    build-commit-sha
    features/csv?
    features/java-nio?
    features/java-time?
    features/xml?
    features/yaml?
    features/jdbc?
    features/postgresql?
    features/sqlite?
    features/hsqldb?
    features/oracledb?
    features/httpkit-client?
    features/lanterna?
    features/core-match?
    features/hiccup?
    features/test-check?
    features/spec-alpha?
    features/selmer?
    features/logging?
    features/priority-map?)))

(defn read-file [file]
  (let [f (io/file file)]
    (if (.exists f)
      (as-> (slurp file) x
        ;; remove shebang
        (str/replace x #"^#!.*" ""))
      (throw (Exception. (str "File does not exist: " file))))))

(defn load-file* [f]
  (let [f (io/file f)
        s (slurp f)]
    (sci/with-bindings {sci/ns @sci/ns
                        sci/file (.getAbsolutePath f)}
      (sci/eval-string* (common/ctx) s))))

(defn start-socket-repl! [address ctx]
  (socket-repl/start-repl! address ctx))

(defn start-nrepl! [address]
  (let [opts (nrepl-server/parse-opt address)]
    (babashka.impl.nrepl-server/start-server! opts))
  (binding [*out* *err*]
    (println "For more info visit: https://book.babashka.org/#_nrepl"))
  ;; hang until SIGINT
  @(promise))

(def aliases
  (cond->
      '{str clojure.string
        set clojure.set
        tools.cli clojure.tools.cli
        edn clojure.edn
        wait babashka.wait
        signal babashka.signal
        shell clojure.java.shell
        io clojure.java.io
        json cheshire.core
        curl babashka.curl
        fs babashka.fs
        bencode bencode.core
        deps babashka.deps
        async clojure.core.async}
    features/xml? (assoc 'xml 'clojure.data.xml)
    features/yaml? (assoc 'yaml 'clj-yaml.core)
    features/jdbc? (assoc 'jdbc 'next.jdbc)
    features/csv? (assoc 'csv 'clojure.data.csv)
    features/transit? (assoc 'transit 'cognitect.transit)))

;;(def ^:private server-ns-obj (sci/create-ns 'clojure.core.server nil))

(def input-var (sci/new-dynamic-var '*input*))

(def clojure-main-ns (sci/create-ns 'clojure.main))

(defn catvec [& xs]
  (into [] cat xs))

(def main-var (sci/new-var 'main nil {:ns clojure-main-ns}))

(def namespaces
  (cond->
      {'user {'*input* (reify
                         sci-types/Eval
                         (eval [_ _ctx _bindings]
                           (force @input-var)))}
       'clojure.core core-extras
       'clojure.tools.cli tools-cli-namespace
       'clojure.java.shell shell-namespace
       'babashka.core bbcore/core-namespace
       'babashka.nrepl.server nrepl-server-namespace
       'babashka.wait wait-namespace
       'babashka.signal signal-ns
       'clojure.java.io io-namespace
       'cheshire.core cheshire-core-namespace
       'cheshire.factory cheshire-factory-namespace
       'clojure.data data/data-namespace
       'clojure.instant instant/instant-namespace
       'clojure.stacktrace stacktrace-namespace
       'clojure.zip zip-namespace
       'clojure.main {:obj clojure-main-ns
                      'demunge (sci/copy-var demunge clojure-main-ns)
                      'repl-requires (sci/copy-var clojure-main/repl-requires clojure-main-ns)
                      'repl (sci/new-var 'repl
                                         (fn [& opts]
                                           (let [opts (apply hash-map opts)]
                                             (repl/start-repl! (common/ctx) opts))) {:ns clojure-main-ns})
                      'with-bindings (sci/copy-var clojure-main/with-bindings clojure-main-ns)
                      'repl-caught (sci/copy-var repl/repl-caught clojure-main-ns)
                      'with-read-known (sci/copy-var clojure-main/with-read-known clojure-main-ns)
                      'main main-var}
       'clojure.test t/clojure-test-namespace
       'clojure.math math-namespace
       'clojure.java.process cjp-namespace
       'babashka.classpath classpath-namespace
       'babashka.classes classes-namespace
       'clojure.pprint pprint-namespace
       'babashka.curl curl-namespace
       'babashka.fs fs-namespace
       'babashka.pods pods/pods-namespace
       'bencode.core bencode-namespace
       'clojure.java.browse browse-namespace
       'clojure.datafy datafy-namespace
       'clojure.core.protocols protocols-namespace
       'babashka.process process-namespace
       'clojure.core.server clojure-core-server-namespace
       'babashka.deps deps-namespace
       'babashka.tasks tasks-namespace
       'clojure.tools.reader.edn edn-namespace
       'clojure.tools.reader.reader-types reader-types-namespace
       'clojure.tools.reader reader-namespace
       'clojure.core.async async-namespace
       'clojure.core.async.impl.protocols async-protocols-namespace
       'clojure.reflect reflect-namespace
       'rewrite-clj.node rewrite/node-namespace
       'rewrite-clj.paredit rewrite/paredit-namespace
       'rewrite-clj.parser rewrite/parser-namespace
       'rewrite-clj.zip rewrite/zip-namespace
       'rewrite-clj.zip.subedit rewrite/subedit-namespace
       'clojure.core.rrb-vector (if features/rrb-vector?
                                  @(resolve 'babashka.impl.rrb-vector/rrb-vector-namespace)
                                  {'catvec (sci/copy-var catvec
                                                         (sci/create-ns 'clojure.core.rrb-vector))})
       'edamame.core edamame-namespace
       'sci.core sci-core-namespace
       'babashka.cli cli/cli-namespace
       'babashka.http-client http-client-namespace
       'babashka.http-client.websocket http-client-websocket-namespace
       'babashka.http-client.interceptors http-client-interceptors-namespace
       'nextjournal.markdown md/markdown-namespace
       'nextjournal.markdown.utils md/markdown-utils-namespace}
    features/xml? (assoc 'clojure.data.xml @(resolve 'babashka.impl.xml/xml-namespace)
                         'clojure.data.xml.event @(resolve 'babashka.impl.xml/xml-event-namespace)
                         'clojure.data.xml.tree @(resolve 'babashka.impl.xml/xml-tree-namespace))
    features/yaml? (assoc 'clj-yaml.core @(resolve 'babashka.impl.yaml/yaml-namespace)
                          'flatland.ordered.map @(resolve 'babashka.impl.ordered/ordered-map-ns)
                          'flatland.ordered.set @(resolve 'babashka.impl.ordered/ordered-set-ns))
    features/jdbc? (assoc 'next.jdbc @(resolve 'babashka.impl.jdbc/njdbc-namespace)
                          'next.jdbc.sql @(resolve 'babashka.impl.jdbc/next-sql-namespace)
                          'next.jdbc.result-set @(resolve 'babashka.impl.jdbc/result-set-namespace))
    features/csv? (assoc 'clojure.data.csv @(resolve 'babashka.impl.csv/csv-namespace))
    features/transit? (assoc 'cognitect.transit @(resolve 'babashka.impl.transit/transit-namespace))
    features/datascript? (assoc 'datascript.core @(resolve 'babashka.impl.datascript/datascript-namespace)
                                'datascript.db @(resolve 'babashka.impl.datascript/datascript-db-namespace))
    features/httpkit-client? (assoc 'org.httpkit.client @(resolve 'babashka.impl.httpkit-client/httpkit-client-namespace)
                                    'org.httpkit.sni-client @(resolve 'babashka.impl.httpkit-client/sni-client-namespace))
    features/httpkit-server? (assoc 'org.httpkit.server @(resolve 'babashka.impl.httpkit-server/httpkit-server-namespace))
    features/lanterna? (assoc 'lanterna.screen @(resolve 'babashka.impl.lanterna/lanterna-screen-namespace)
                              'lanterna.terminal @(resolve 'babashka.impl.lanterna/lanterna-terminal-namespace)
                              'lanterna.constants @(resolve 'babashka.impl.lanterna/lanterna-constants-namespace))
    features/core-match? (assoc 'clojure.core.match @(resolve 'babashka.impl.match/core-match-namespace))
    features/hiccup? (-> (assoc 'hiccup.core @(resolve 'babashka.impl.hiccup/hiccup-namespace))
                         (assoc 'hiccup2.core @(resolve 'babashka.impl.hiccup/hiccup2-namespace))
                         (assoc 'hiccup.util @(resolve 'babashka.impl.hiccup/hiccup-util-namespace))
                         (assoc 'hiccup.compiler @(resolve 'babashka.impl.hiccup/hiccup-compiler-namespace)))
    ;; ensure load before babashka.impl.clojure.spec.alpha for random patch!
    features/test-check? (assoc 'clojure.test.check.random
                                @(resolve 'babashka.impl.clojure.test.check/random-namespace)
                                'clojure.test.check.generators
                                @(resolve 'babashka.impl.clojure.test.check/generators-namespace)
                                'clojure.test.check.rose-tree
                                @(resolve 'babashka.impl.clojure.test.check/rose-tree-namespace)
                                'clojure.test.check.properties
                                @(resolve 'babashka.impl.clojure.test.check/properties-namespace)
                                'clojure.test.check
                                @(resolve 'babashka.impl.clojure.test.check/test-check-namespace)
                                ;; it's better to load this from source by adding the clojure.test.check dependency
                                #_#_'clojure.test.check.clojure-test
                                @(resolve 'babashka.impl.clojure.test.check/test-check-clojure-test-namespace))
    features/spec-alpha? (-> (assoc ;; spec
                              'clojure.spec.alpha @(resolve 'babashka.impl.spec/spec-namespace)
                              'clojure.spec.gen.alpha @(resolve 'babashka.impl.spec/gen-namespace)
                              'clojure.spec.test.alpha @(resolve 'babashka.impl.spec/test-namespace)))
    features/selmer? (assoc 'selmer.parser
                            @(resolve 'babashka.impl.selmer/selmer-parser-namespace)
                            'selmer.tags
                            @(resolve 'babashka.impl.selmer/selmer-tags-namespace)
                            'selmer.filters
                            @(resolve 'babashka.impl.selmer/selmer-filters-namespace)
                            'selmer.util
                            @(resolve 'babashka.impl.selmer/selmer-util-namespace)
                            'selmer.validator
                            @(resolve 'babashka.impl.selmer/selmer-validator-namespace))
    features/logging? (assoc 'taoensso.timbre @(resolve 'babashka.impl.logging/timbre-namespace)
                             'taoensso.timbre.appenders.core @(resolve 'babashka.impl.logging/timbre-appenders-namespace)
                             'taoensso.encore @(resolve 'babashka.impl.logging/encore-namespace)
                             'taoensso.truss @(resolve 'babashka.impl.logging/truss-namespace)
                             'clojure.tools.logging
                             @(resolve 'babashka.impl.logging/tools-logging-namespace)
                             'clojure.tools.logging.impl
                             @(resolve 'babashka.impl.logging/tools-logging-impl-namespace)
                             'clojure.tools.logging.readable
                             @(resolve 'babashka.impl.logging/tools-logging-readable-namespace))
    features/priority-map? (assoc 'clojure.data.priority-map
                                  @(resolve 'babashka.impl.priority-map/priority-map-namespace))))

(def edn-readers (cond-> {}
                   features/yaml?
                   (assoc 'ordered/map @(resolve 'flatland.ordered.map/ordered-map)
                          'ordered/set @(resolve 'flatland.ordered.set/ordered-set))
                   features/xml?
                   (assoc 'xml/ns @(resolve 'clojure.data.xml.name/uri-symbol)
                          'xml/element @(resolve 'clojure.data.xml.node/tagged-element))))

;; also put the edn readers into *data-readers*
(sci/alter-var-root core/data-readers into edn-readers)

(defn edn-seq*
  [^java.io.BufferedReader rdr]
  (let [edn-val (edn/read {:eof ::EOF :readers edn-readers :default tagged-literal} rdr)]
    (when (not (identical? ::EOF edn-val))
      (cons edn-val (lazy-seq (edn-seq* rdr))))))

(defn edn-seq
  [in]
  (edn-seq* in))

(defn shell-seq [in]
  (line-seq (java.io.BufferedReader. in)))

(defn parse-run-opts [opts-map args]
  (loop [opts-map opts-map
         args (seq args)]
    (if args
      (let [fst (first args)]
        (case fst
          "--help"
          (recur (assoc opts-map :run true :run-help true)
                 (next args))
          "--parallel"
          (recur (assoc opts-map :parallel-tasks true)
                 (next args))
          "--prn"
          (let [args (next args)]
            (recur (assoc opts-map :prn true)
                   args))
          ;; default
          (assoc opts-map :run fst :command-line-args (next args))))
      opts-map)))

(defn- parse-print-deps-opts
  [opts-map args]
  (loop [opts-map (assoc opts-map :print-deps true)
         args (seq args)]
    (if args
      (let [fst (first args)]
        (case fst
          "--format"
          (recur (assoc opts-map :print-deps-format (second args))
                 (nnext args))
          opts-map))
      opts-map)))

(defn parse-args [args opts-map]
  (loop [options args
         opts-map opts-map]
    (if options
      (let [opt (first options)]
        (case opt
          ("--") (assoc opts-map :command-line-args (next options))
          ("--clojure") (assoc opts-map :clojure true
                               :command-line-args (rest options))
          ("--version") {:version true}
          ("--help" "-h" "-?" "help")
          {:help true
           :command-line-args (rest options)}
          ("--doc")
          {:doc true
           :command-line-args (rest options)}
          ;; renamed to --debug
          ("--verbose") (recur (next options)
                               (assoc opts-map
                                      :verbose? true))
          ("--describe") (recur (next options)
                                (assoc opts-map
                                       :describe? true))
          ("--stream") (recur (next options)
                              (assoc opts-map
                                     :stream? true))
          ("-i") (recur (next options)
                        (assoc opts-map
                               :shell-in true))
          ("-I") (recur (next options)
                        (assoc opts-map
                               :edn-in true))
          ("-o") (recur (next options)
                        (assoc opts-map
                               :shell-out true :prn true))
          ("-O") (recur (next options)
                        (assoc opts-map
                               :edn-out true :prn true))
          ("-io") (recur (next options)
                         (assoc opts-map
                                :shell-in true
                                :shell-out true))
          ("-iO") (recur (next options)
                         (assoc opts-map
                                :shell-in true
                                :edn-out true))
          ("-Io") (recur (next options)
                         (assoc opts-map
                                :edn-in true
                                :shell-out true))
          ("-IO") (recur (next options)
                         (assoc opts-map
                                :edn-in true
                                :edn-out true))
          ("--classpath", "-cp")
          (let [options (next options)]
            (recur (next options)
                   (assoc opts-map :classpath (first options))))
          ("--uberscript")
          (let [options (next options)]
            (recur (next options)
                   (assoc opts-map
                          :uberscript (first options))))
          ("--uberjar")
          (let [options (next options)]
            (recur (next options)
                   (assoc opts-map
                          :uberjar (first options))))
          ("--repl")
          (let [options (next options)]
            (recur options
                   (assoc opts-map
                          :repl true)))
          ("--socket-repl")
          (let [options (next options)
                opt (first options)
                opt (when (and opt (not (str/starts-with? opt "-")))
                      opt)
                options (if opt (next options)
                            options)]
            (recur options
                   (assoc opts-map
                          :socket-repl (or opt "1666"))))
          ("--nrepl-server")
          (let [options (next options)
                opt (first options)
                opt (when (and opt (not (str/starts-with? opt "-")))
                      opt)
                options (if opt (next options)
                            options)]
            (recur options
                   (assoc opts-map
                          :nrepl (or opt "1667"))))
          ("--eval", "-e")
          (let [options (next options)
                opts-map (assoc opts-map :prn true)]
            (recur (next options)
                   (update opts-map :expressions (fnil conj []) (first options))))
          ("--main", "-m")
          (let [options (next options)]
            (assoc opts-map :main (first options)
                   :command-line-args (if (= "--" (second options))
                                        (nthrest options 2)
                                        (rest options))))
          ("--exec", "-x")
          (let [options (next options)]
            (assoc opts-map :exec (first options)
                   :command-line-args (if (= "--" (second options))
                                        (nthrest options 2)
                                        (rest options))))
          ("--run")
          (parse-run-opts opts-map (next options))
          ("--tasks")
          (assoc opts-map :list-tasks true
                 :command-line-args (next options))
          ("--print-deps")
          (parse-print-deps-opts opts-map (next options))
          ("--prepare")
          (let [options (next options)]
            (recur (next options)
                   (assoc opts-map :prepare true)))
          ;; fallback
          (if (and opts-map
                   (some opts-map [:file :jar :socket-repl :expressions :main :run]))
            (assoc opts-map
                   :command-line-args options)
            (let [trimmed-opt (str/triml opt)
                  c (.charAt trimmed-opt 0)]
              (case c
                (\( \{ \[ \* \@ \#)
                (-> opts-map
                    (assoc :prn true)
                    (update :expressions (fnil conj []) (first options))
                    (assoc :command-line-args (next options)))
                (assoc opts-map
                       (if (str/ends-with? opt ".jar")
                         :jar
                         :file) opt
                       :command-line-args (next options)))))))
      opts-map)))

(defn parse-global-opts [options]
  (loop [options (seq options)
         opts-map {}]
    (if options
      (case (first options)
        ("--classpath" "-cp") (recur (nnext options) (assoc opts-map :classpath (second options)))

        ("--debug"
         "--verbose")
        ;; renamed to --debug
        (recur (next options) (assoc opts-map :debug true))

        ("--init")
        (recur (nnext options) (assoc opts-map :init (second options)))

        ("--force" "-Sforce")
        (recur (next options) (assoc opts-map :force? true))

        ("-Sdeps")
        (recur (nnext options) (assoc opts-map :merge-deps (second options)))

        ("--config")
        (recur (nnext options) (assoc opts-map :config (second options)))

        ("--deps-root")
        (recur (nnext options) (assoc opts-map :deps-root (second options)))
        ("--prn")
        (recur (next options) (assoc opts-map :prn true))
        ("-f" "--file")
        (recur (nnext options) (assoc opts-map :file (second options)))
        ("-jar" "--jar")
        (recur (nnext options) (assoc opts-map :jar (second options)))
        [options opts-map])
      [options opts-map])))

(defn parse-file-opt
  [options opts-map]
  (let [opt (first options)]
    (if (and opt (and (fs/exists? opt)
                      (not (fs/directory? opt))))
      [nil (assoc opts-map
                  (if (str/ends-with? opt ".jar")
                    :jar :file) opt
                  :command-line-args (next options))]
      [options opts-map])))

(defn parse-opts
  ([options] (parse-opts options nil))
  ([options opts-map]
   (let [opt (first options)
         task-map (:tasks @common/bb-edn)
         tasks (into #{} (map str) (keys task-map))]
     (when-let [commands (seq (filter (fn [task]
                                        (and (command? task)
                                             (not (:override-builtin (get task-map (symbol task))))))
                                      tasks))]
       (binding [*out* *err*]
         (println "[babashka] WARNING: task(s)" (str/join ", " (map #(format "'%s'" %) commands)) "override built-in command(s). Use :override-builtin true to disable warning.")))
     (if-not opt opts-map
             ;; FILE > TASK > SUBCOMMAND
             (cond
               (and (not (or (:file opts-map)
                             (:jar opts-map)))
                    (.isFile (io/file opt)))
               (parse-file-opt options opts-map)
               (contains? tasks opt)
               (assoc opts-map
                      :run opt
                      :command-line-args (next options))
               (command? opt)
               (recur (cons (str "--" opt) (next options)) opts-map)

               :else
               (parse-args options opts-map))))))

(def env (atom {}))

(def pod-namespaces (volatile! {}))

(defn download-only?
  "If we're preparing pods for another OS / arch, don't try to run them."
  []
  (let [env-os-name (System/getenv "BABASHKA_PODS_OS_NAME")
        env-os-name-present? (not (str/blank? env-os-name))
        sys-os-name (System/getProperty "os.name")
        env-os-arch (System/getenv "BABASHKA_PODS_OS_ARCH")
        env-os-arch-present? (not (str/blank? env-os-arch))
        sys-os-arch (System/getProperty "os.arch")]
    (when @common/debug
      (binding [*out* *err*]
        (println "System OS name:" sys-os-name)
        (when env-os-name-present? (println "BABASHKA_PODS_OS_NAME:" env-os-name))
        (println "System OS arch:" sys-os-arch)
        (when env-os-arch-present? (println "BABASHKA_PODS_OS_ARCH:" env-os-arch))))
    (cond
      env-os-name-present? (not= env-os-name sys-os-name)
      env-os-arch-present? (not= env-os-arch sys-os-arch))))

(defn file-write-allowed?
  "For output file of uberscript/uberjar, allow writing of jar files
   and files that are empty/don't exist."
  [path]
  (or (= "jar" (fs/extension path))
      (not (fs/exists? path))))

(def seen-urls (atom nil))

(defn read-data-readers [url]
  (edamame/parse-string (slurp url)
                        {:read-cond :allow
                         :features #{:bb :clj}
                         :eof nil}))

(defn readers-fn
  "Lazy reading of data reader functions"
  [ctx t]
  (or (@core/data-readers t)
      (default-data-readers t)
      (when (simple-symbol? t)
        (when-let [the-var (sci/resolve ctx t)]
          (some-> the-var meta :sci.impl.record/map-constructor)))
      (when-let [f @sci.impl.parser/default-data-reader-fn]
        (fn [form]
          (f t form)))
      (let [;; urls is a vector for equality check
            urls (vec (.getURLs ^java.net.URLClassLoader @cp/the-url-loader))
            parsed-resources (or (get @seen-urls urls)
                                 (let [^java.net.URLClassLoader cl @cp/the-url-loader
                                       resources (concat (enumeration-seq (.getResources cl "data_readers.clj"))
                                                         (enumeration-seq (.getResources cl "data_readers.cljc")))
                                       parsed-resources (apply merge (map read-data-readers resources))
                                       _ (swap! seen-urls assoc urls parsed-resources)]
                                   parsed-resources))]
        (when-let [var-sym (get parsed-resources t)]
          (when-let [the-var (sci/resolve ctx var-sym)]
            (sci/eval-form ctx (list 'clojure.core/var-set core/data-readers (list 'quote (assoc @core/data-readers t the-var))))
            the-var)))))

(defn exec [cli-opts]
  (with-bindings {#'*unrestricted* true
                  clojure.lang.Compiler/LOADER @cp/the-url-loader}
    (sci/binding [core/warn-on-reflection @core/warn-on-reflection
                  core/unchecked-math @core/unchecked-math
                  core/data-readers @core/data-readers
                  sci/ns @sci/ns
                  sci/print-length @sci/print-length
                  ;; when adding vars here, also add them to repl.clj and nrepl_server.clj
                  ]
      (let [{:keys [:shell-in :edn-in :shell-out :edn-out
                    :file :command-line-args
                    :expressions :stream? :init
                    :repl :socket-repl :nrepl
                    :debug :classpath :force?
                    :main :uberscript
                    :jar :uberjar :clojure
                    :doc :run :list-tasks
                    :print-deps :prepare]
             exec-fn :exec}
            cli-opts
            print-result? (:prn cli-opts)
            _ (when debug (vreset! common/debug true))
            _ (do ;; set properties
                (when main (System/setProperty "babashka.main" main))
                ;; TODO: what about exec here?
                (System/setProperty "babashka.version" version))
            read-next (fn [*in*]
                        (if (pipe-signal-received?)
                          ::EOF
                          (if stream?
                            (if shell-in (or (read-line) ::EOF)
                                (edn/read {:readers edn-readers
                                           :default tagged-literal
                                           :eof ::EOF} *in*))
                            (delay (cond shell-in
                                         (shell-seq *in*)
                                         edn-in
                                         (edn-seq *in*)
                                         :else
                                         (edn/read {:readers edn-readers
                                                    :default tagged-literal} *in*))))))
            uberscript-sources (atom ())
            classpath (or classpath
                          (System/getenv "BABASHKA_CLASSPATH"))
            _ (if classpath
                (cp/add-classpath classpath)
                ;; when classpath isn't set, we calculate it from bb.edn, if present
                (when-let [bb-edn @common/bb-edn] (deps/add-deps bb-edn {:force force?})))
            abs-path (when file
                       (let [abs-path (.getAbsolutePath (io/file file))]
                         (vars/bindRoot sci/file abs-path)
                         (System/setProperty "babashka.file" abs-path)
                         abs-path))
            _ (when jar
                (cp/add-classpath jar))
            load-fn (fn [{:keys [namespace reload ctx]}]
                      (let [loader @cp/the-url-loader]
                        (or
                         (when ;; ignore built-in namespaces when uberscripting, unless with :reload
                             (and uberscript
                                  (not reload)
                                  (or (contains? namespaces namespace)
                                      (contains? sci-namespaces/namespaces namespace)))
                           "")
                         ;; pod namespaces go before namespaces from source,
                         ;; unless reload is used
                         (when-not reload
                           (when-let [pod (get @pod-namespaces namespace)]
                             (if uberscript
                               (do
                                 (swap! uberscript-sources conj
                                        (format
                                         "(babashka.pods/load-pod '%s \"%s\" '%s)\n"
                                         (:pod-spec pod) (:version (:opts pod))
                                         (dissoc (:opts pod)
                                                 :version :metadata)))
                                 {})
                               (pods/load-pod (:pod-spec pod) (:opts pod)))))
                         (when loader
                           (when-let [res (cp/source-for-namespace loader namespace nil)]
                             (if uberscript
                               (do (swap! uberscript-sources conj (:source res))
                                   (uberscript/uberscript {:ctx (common/ctx)
                                                           :expressions [(:source res)]})
                                   {})
                               res)))
                         (let [rps (cp/resource-paths namespace)
                               rps (mapv #(str "src/babashka/" %) rps)]
                           (when-let [url (some io/resource rps)]
                             (let [source (slurp url)]
                               {:file (str url)
                                :source source})))
                         (case namespace
                           clojure.spec.alpha
                           (binding [*out* *err*]
                             (println "[babashka] WARNING: Use the babashka-compatible version of clojure.spec.alpha, available here: https://github.com/babashka/spec.alpha"))
                           clojure.core.specs.alpha
                           (binding [*out* *err*]
                             (println "[babashka] WARNING: clojure.core.specs.alpha is removed from the classpath, unless you explicitly add the dependency."))
                           (when-not (sci/find-ns ctx namespace)
                             (let [file (str/replace (namespace-munge namespace) "." "/")]
                               (throw (new java.io.FileNotFoundException (format "Could not locate %s.bb, %s.clj or %s.cljc on classpath." file file file)))))))))
            main (if (and jar (not main))
                   (when-let [res (cp/getResource
                                   (cp/new-loader [jar])
                                   ["META-INF/MANIFEST.MF"] {:url? true})]
                     (cp/main-ns res))
                   main)
            ;; TODO: pull more of these values to compile time
            opts {:aliases aliases
                  :namespaces (assoc-in namespaces ['clojure.core 'load-file] (sci-copy-vars/new-var 'load-file load-file*))
                  :env env
                  :features #{:bb :clj}
                  :classes @classes/class-map
                  :imports classes/imports
                  :load-fn load-fn
                  :uberscript uberscript
                  ;; :readers core/data-readers
                  :reify-fn reify-fn
                  :proxy-fn proxy-fn
                  :readers #(readers-fn (common/ctx) %)}
            opts (addons/future opts)
            sci-ctx (sci/init opts)
            _ (ctx-store/reset-ctx! sci-ctx)
            _ (when-let [pods (:pods @common/bb-edn)]
                (when-let [pod-metadata (pods/load-pods-metadata
                                         pods {:download-only (download-only?)})]
                  (vreset! pod-namespaces pod-metadata)))
            preloads (some-> (System/getenv "BABASHKA_PRELOADS") (str/trim))
            [expressions exit-code]
            (cond expressions [expressions nil]
                  main
                  (let [sym (symbol main)
                        ns? (namespace sym)
                        ns (or ns? sym)
                        var-name (if ns?
                                   (name sym)
                                   "-main")]
                    [[(format "(ns user (:require [%1$s])) (apply %1$s/%2$s *command-line-args*)"
                              ns var-name)] nil])
                  exec-fn
                  (let [sym (symbol exec-fn)]
                    [[(cli/exec-fn-snippet sym)] nil])
                  run (if (:run-help cli-opts)
                        [(print-run-help) 0]
                        (do
                          (System/setProperty "babashka.task" (str run))
                          (tasks/assemble-task run
                                               (:parallel-tasks cli-opts))))
                  file (try [[(read-file file)] nil]
                            (catch Exception e
                              (error-handler e {:expression expressions
                                                :debug debug
                                                :preloads preloads
                                                :init init
                                                :loader @cp/the-url-loader}))))
            expression (str/join " " expressions) ;; this might mess with the locations...
            exit-code
            ;; handle preloads
            (if exit-code exit-code
                (do (when preloads
                      (sci/binding [sci/file "<preloads>"]
                        (try
                          (sci/eval-string* sci-ctx preloads)
                          (catch Throwable e
                            (error-handler e {:expression expression
                                              :debug debug
                                              :preloads preloads
                                              :init init
                                              :loader @cp/the-url-loader})))))
                    nil))
            exit-code
            ;; handle --init
            (if exit-code exit-code
                (do (when init
                      (try
                        (load-file* init)
                        (catch Throwable e
                          (error-handler e {:expression expression
                                            :debug debug
                                            :preloads preloads
                                            :init init
                                            :loader @cp/the-url-loader}))))
                    nil))
            ;; socket REPL is start asynchronously. when no other args are
            ;; provided, a normal REPL will be started as well, which causes the
            ;; process to wait until SIGINT
            _ (when socket-repl (start-socket-repl! socket-repl sci-ctx))
            exit-code
            (or exit-code
                (second
                 (cond doc (print-doc sci-ctx command-line-args)
                       repl (sci/binding [core/command-line-args command-line-args]
                              [(repl/start-repl! sci-ctx) 0])
                       nrepl [(start-nrepl! nrepl) 0]
                       uberjar [nil 0]
                       list-tasks [(tasks/list-tasks sci-ctx) 0]
                       print-deps [(print-deps/print-deps (:print-deps-format cli-opts)) 0]
                       prepare [nil 0]
                       uberscript
                       [nil (do (uberscript/uberscript {:ctx sci-ctx
                                                        :expressions expressions})
                                0)]
                       expressions
                       ;; execute code
                       (sci/binding [sci/file abs-path]
                         (try
                           ;; when evaluating expression(s), add in repl-requires so things like
                           ;; pprint and dir are available
                           (sci/eval-form sci-ctx `(apply require (quote ~clojure-main/repl-requires)))
                           (loop []
                             (let [in (read-next *in*)]
                               (if (identical? ::EOF in)
                                 [nil 0] ;; done streaming
                                 (let [res [(let [res
                                                  (sci/binding [sci/file (or @sci/file "<expr>")
                                                                input-var in
                                                                core/command-line-args command-line-args]
                                                    (sci/eval-string* sci-ctx expression))]
                                              ;; return value printing
                                              (when (and (some? res)
                                                         print-result?)
                                                (if-let [pr-f (cond shell-out println
                                                                    edn-out sio/prn)]
                                                  (if (sequential? res)
                                                    (doseq [l res
                                                            :while (not (pipe-signal-received?))]
                                                      (pr-f l))
                                                    (pr-f res))
                                                  (sio/prn res)))) 0]]
                                   (if stream?
                                     (recur)
                                     res)))))
                           (catch Throwable e
                             (error-handler e {:expression expression
                                               :debug debug
                                               :preloads preloads
                                               :loader @cp/the-url-loader}))))
                       clojure [nil (if-let [proc (bdeps/clojure command-line-args)]
                                      (-> @proc :exit)
                                      0)]
                       :else (sci/binding [core/command-line-args command-line-args]
                               [(repl/start-repl! sci-ctx) 0])))
                1)]
        (flush)
        (when uberscript
          (if (file-write-allowed? uberscript)
            (do
              (spit uberscript "") ;; reset file
              (doseq [s (distinct @uberscript-sources)]
                (spit uberscript s :append true))
              (spit uberscript preloads :append true)
              (spit uberscript expression :append true))
            (throw (Exception. (str "Uberscript target file '" uberscript
                                    "' exists and is not empty. Overwrite prohibited.")))))
        (when uberjar
          (let [cp (cp/get-classpath)]
            (cond
              (not (file-write-allowed? uberjar))
              (throw (Exception. (str "Uberjar target file '" uberjar
                                      "' exists and is not empty. Overwrite prohibited.")))
              (not cp)
              (throw (Exception. "The uberjar task needs a classpath."))
              :else
              (let [uber-params {:dest uberjar
                                 :jar :uber
                                 :classpath cp
                                 :main-class main
                                 :verbose debug}]
                (if-let [bb-edn-pods (:pods @common/bb-edn)]
                  (fs/with-temp-dir [bb-edn-dir {}]
                    (let [bb-edn-resource (fs/file bb-edn-dir "META-INF" "bb.edn")]
                      (fs/create-dirs (fs/parent bb-edn-resource))
                      (->> {:pods bb-edn-pods} pr-str (spit bb-edn-resource))
                      (let [cp-with-bb-edn (str bb-edn-dir cp/path-sep cp)]
                        (uberjar/run (assoc uber-params
                                            :classpath cp-with-bb-edn)))))
                  (uberjar/run uber-params))))))
        exit-code))))

(defn exec-without-deps [cli-opts]
  (let [{version-opt :version
         :keys [help describe?]} cli-opts]
    (cond
      version-opt (print-version)
      help        (print-help)
      describe?   (print-describe)))
  0)

(defn satisfies-min-version? [min-version]
  (let [[major-current minor-current patch-current] version-data
        [major-min minor-min patch-min] (parse-version min-version)]
    (or (> major-current major-min)
        (and (= major-current major-min)
             (or (> minor-current minor-min)
                 (and (= minor-current minor-min)
                      (>= patch-current patch-min)))))))

(defn read-bb-edn [string]
  (try (edn/read-string {:default tagged-literal :eof nil} string)
       (catch java.lang.RuntimeException e
         (if (re-find #"No dispatch macro for: \"" (.getMessage e))
           (throw (ex-info "Invalid regex literal found in EDN config, use re-pattern instead" {}))
           (do (binding [*out* *err*]
                 (println "Error during loading bb.edn:"))
               (throw e))))))

(defn binary-invoked-as-jar []
  (and (= "executable" (System/getProperty "org.graalvm.nativeimage.kind"))
       (when-let [bin (-> (java.lang.ProcessHandle/current)
                          .info
                          .command
                          (.orElse nil))]
         (let [fn (fs/file-name bin)]
           (if (= "bb" fn)
             false
             (if (and (fs/windows?)
                      (= "bb.exe" fn))
               false
               (when (try (with-open [_ (java.util.zip.ZipFile. (fs/file bin))])
                          true
                          (catch Exception _ false))
                 bin)))))))

(defn resolve-symbolic-link [f]
  (if (and f (fs/exists? f))
    (try
      (str (fs/real-path f))
      (catch Exception _
        f))
    f))

(defn deps-not-needed [opts]
  (let [fast-path-opts [:version :help :describe?]]
    (some #(contains? opts %) fast-path-opts)))

(defn main [& args]
  (let [bin-jar (binary-invoked-as-jar)
        args (if bin-jar
               (list* "--jar" bin-jar "--" args)
               args)
        [args opts] (parse-global-opts args)
        [args {:keys [config merge-deps debug] :as opts}]
        (if-not (or (:file opts)
                    (:jar opts))
          (parse-file-opt args opts)
          [args opts])
        {:keys [jar file]} opts
        abs-path resolve-symbolic-link
        config (cond
                 config (if (fs/exists? config) (abs-path config)
                            (when debug
                              (binding [*out* *err*]
                                (println "[babashka] WARNING: config file does not exist:" config))
                              nil))
                 jar (let [jar (resolve-symbolic-link jar)]
                       (some->> [jar] cp/new-loader (cp/resource "META-INF/bb.edn") .toString))
                 :else (if (and file (fs/exists? file))
                         ;; file relative to bb.edn
                         (let [file (abs-path file) ;; follow symlink
                               rel-bb-edn (fs/file (fs/parent file) "bb.edn")]
                           (if (fs/exists? rel-bb-edn)
                             (abs-path rel-bb-edn)
                             ;; fall back to local bb.edn
                             (when (fs/exists? "bb.edn")
                               (abs-path "bb.edn"))))
                         ;; default to local bb.edn
                         (when (fs/exists? "bb.edn")
                           (abs-path "bb.edn"))))
        bb-edn (when (or config merge-deps)
                 (when config (System/setProperty "babashka.config" config))
                 (let [raw-string (when config (slurp config))
                       edn (when config (read-bb-edn raw-string))
                       edn (if merge-deps
                             (deps/merge-deps [edn (read-bb-edn merge-deps)])
                             edn)
                       edn (assoc edn
                                  :raw raw-string
                                  :file config)
                       edn (if-let [deps-root (or (:deps-root opts)
                                                  (some-> config fs/parent))]
                             (assoc edn :deps-root deps-root)
                             edn)]
                   (vreset! common/bb-edn edn)))
        opts (parse-opts args opts)
        min-bb-version (:min-bb-version bb-edn)]
    (System/setProperty "java.class.path" "")
    (when min-bb-version
      (when-not (satisfies-min-version? min-bb-version)
        (binding [*out* *err*]
          (println (str "WARNING: this project requires babashka "
                        min-bb-version " or newer, but you have: " version)))))
    (if (deps-not-needed opts)
      (exec-without-deps opts)
      (exec opts))))

(def musl?
  "Captured at compile time, to know if we are running inside a
  statically compiled executable with musl."
  (and (= "true" (System/getenv "BABASHKA_STATIC"))
       (= "true" (System/getenv "BABASHKA_MUSL"))))

(defmacro run [args]
  (if musl?
    ;; When running in musl-compiled static executable we lift execution of bb
    ;; inside a thread, so we have a larger than default stack size, set by an
    ;; argument to the linker. See https://github.com/oracle/graal/issues/3398
    `(let [v# (volatile! nil)
           f# (fn []
                (vreset! v# (apply main ~args)))]
       (doto (Thread. nil f# "main")
         (.start)
         (.join))
       @v#)
    `(apply main ~args)))

(defn -main
  [& args]
  (handle-pipe!)
  (handle-sigint!)
  (if-let [dev-opts (System/getenv "BABASHKA_DEV")]
    (let [{:keys [:n]} (if (= "true" dev-opts) {:n 1}
                           (edn/read-string dev-opts))
          last-iteration (dec n)]
      (dotimes [i n]
        (if (< i last-iteration)
          (with-out-str (apply main args))
          (do (run args)
              (binding [*out* *err*]
                (println "ran" n "times"))))))
    (let [exit-code (run args)]
      (System/exit exit-code))))

(sci/alter-var-root main-var (constantly -main))
;;;; Scratch

(comment)
