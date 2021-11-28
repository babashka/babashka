(ns babashka.main
  {:no-doc true}
  (:refer-clojure :exclude [error-handler])
  (:require
   [aaaa-this-has-to-be-first.because-patches]
   [babashka.deps :as bdeps]
   [babashka.fs :as fs]
   [babashka.impl.bencode :refer [bencode-namespace]]
   [babashka.impl.cheshire :refer [cheshire-core-namespace]]
   [babashka.impl.classes :as classes]
   [babashka.impl.classpath :as cp :refer [classpath-namespace]]
   [babashka.impl.clojure.core :as core :refer [core-extras]]
   [babashka.impl.clojure.core.async :refer [async-namespace async-protocols-namespace]]
   [babashka.impl.clojure.java.browse :refer [browse-namespace]]
   [babashka.impl.clojure.java.io :refer [io-namespace]]
   [babashka.impl.clojure.java.shell :refer [shell-namespace]]
   [babashka.impl.clojure.main :as clojure-main :refer [demunge]]
   [babashka.impl.clojure.stacktrace :refer [stacktrace-namespace]]
   [babashka.impl.clojure.zip :refer [zip-namespace]]
   [babashka.impl.common :as common]
   [babashka.impl.curl :refer [curl-namespace]]
   [babashka.impl.data :as data]
   [babashka.impl.datafy :refer [datafy-namespace]]
   [babashka.impl.deps :as deps :refer [deps-namespace]]
   [babashka.impl.error-handler :refer [error-handler]]
   [babashka.impl.features :as features]
   [babashka.impl.fs :refer [fs-namespace]]
   [babashka.impl.pods :as pods]
   [babashka.impl.pprint :refer [pprint-namespace]]
   [babashka.impl.print-deps :as print-deps]
   [babashka.impl.process :refer [process-namespace]]
   [babashka.impl.protocols :refer [protocols-namespace]]
   [babashka.impl.proxy :refer [proxy-fn]]
   [babashka.impl.reify :refer [reify-fn]]
   [babashka.impl.repl :as repl]
   [babashka.impl.rewrite-clj :as rewrite]
   [babashka.impl.server :refer [clojure-core-server-namespace]]
   [babashka.impl.socket-repl :as socket-repl]
   [babashka.impl.tasks :as tasks :refer [tasks-namespace]]
   [babashka.impl.test :as t]
   [babashka.impl.tools.cli :refer [tools-cli-namespace]]
   [babashka.nrepl.server :as nrepl-server]
   [babashka.wait :refer [wait-namespace]]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [hf.depstar.uberjar :as uberjar]
   [sci.addons :as addons]
   [sci.core :as sci]
   [sci.impl.namespaces :as sci-namespaces]
   [sci.impl.unrestrict :refer [*unrestricted*]]
   [sci.impl.utils :refer [ctx-fn]]
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

(sci/alter-var-root sci/in (constantly *in*))
(sci/alter-var-root sci/out (constantly *out*))
(sci/alter-var-root sci/err (constantly *err*))

(set! *warn-on-reflection* true)
;; To detect problems when generating the image, run:
;; echo '1' | java -agentlib:native-image-agent=config-output-dir=/tmp -jar target/babashka-xxx-standalone.jar '...'
;; with the java provided by GraalVM.

(def version (str/trim (slurp (io/resource "BABASHKA_VERSION"))))
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
     "print-deps") true
    false))

(defn print-error [& msgs]
  (binding [*out* *err*]
    (apply println msgs)))

(defn print-help [_ctx _command-line-args]
  (println (str "Babashka v" version))
  (println "
Usage: bb [svm-opts] [global-opts] [eval opts] [cmdline args]
or:    bb [svm-opts] [global-opts] file [cmdline args]
or:    bb [svm-opts] [global-opts] subcommand [subcommand opts] [cmdline args]

Substrate VM opts:

  -Xmx<size>[g|G|m|M|k|K]  Set a maximum heap size (e.g. -Xmx256M to limit the heap to 256MB).
  -XX:PrintFlags=          Print all Substrate VM options.

Global opts:

  -cp, --classpath  Classpath to use. Overrides bb.edn classpath.
  --debug           Print debug information and internal stacktrace in case of exception.
  --force           Passes -Sforce to deps.clj, forcing recalculation of the classpath.
  --init <file>     Load file after any preloads and prior to evaluation/subcommands.

Help:

  help, -h or -?     Print this help text.
  version            Print the current version of babashka.
  describe           Print an EDN map with information about this version of babashka.
  doc <var|ns>       Print docstring of var or namespace. Requires namespace if necessary.

Evaluation:

  -e, --eval <expr>    Evaluate an expression.
  -f, --file <path>    Evaluate a file.
  -m, --main <ns|var>  Call the -main function from a namespace or call a fully qualified var.

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
When no eval opts or subcommand is provided, the implicit subcommand is repl.")
  [nil 0])

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
      [nil 1]))
  ,)

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
 :feature/csv        %s
 :feature/java-nio   %s
 :feature/java-time  %s
 :feature/xml        %s
 :feature/yaml       %s
 :feature/jdbc       %s
 :feature/postgresql %s
 :feature/hsqldb     %s
 :feature/oracledb   %s
 :feature/httpkit-client %s
 :feature/lanterna %s
 :feature/core-match %s
 :feature/hiccup     %s
 :feature/test-check %s
 :feature/spec-alpha %s
 :feature/selmer %s
 :feature/logging %s}")
    version
    features/csv?
    features/java-nio?
    features/java-time?
    features/xml?
    features/yaml?
    features/jdbc?
    features/sqlite?
    features/postgresql?
    features/hsqldb?
    features/oracledb?
    features/httpkit-client?
    features/lanterna?
    features/core-match?
    features/hiccup?
    features/test-check?
    features/spec-alpha?
    features/selmer?
    features/logging?)))

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
      (sci/eval-string* @common/ctx s))))

(defn start-socket-repl! [address ctx]
  (socket-repl/start-repl! address ctx))

(defn start-nrepl! [address ctx]
  (let [dev? (= "true" (System/getenv "BABASHKA_DEV"))
        nrepl-opts (nrepl-server/parse-opt address)
        nrepl-opts (assoc nrepl-opts
                          :debug dev?
                          :describe {"versions" {"babashka" version}}
                          :thread-bind [core/warn-on-reflection])]
    (nrepl-server/start-server! ctx nrepl-opts)
    (binding [*out* *err*]
      (println "For more info visit: https://book.babashka.org/#_nrepl")))
  ;; hang until SIGINT
  @(promise))

(def aliases
  (cond->
      '{tools.cli clojure.tools.cli
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
    features/xml?        (assoc 'xml 'clojure.data.xml)
    features/yaml?       (assoc 'yaml 'clj-yaml.core)
    features/jdbc?       (assoc 'jdbc 'next.jdbc)
    features/csv?        (assoc 'csv 'clojure.data.csv)
    features/transit?    (assoc 'transit 'cognitect.transit)))

;;(def ^:private server-ns-obj (sci/create-ns 'clojure.core.server nil))

(def input-var (sci/new-dynamic-var '*input*))

(def clojure-main-ns (sci/create-ns 'clojure.main))

(def namespaces
  (cond->
      {'user {'*input* (ctx-fn
                        (fn [_ctx _bindings]
                          (force @input-var))
                        nil)}
       'clojure.tools.cli tools-cli-namespace
       'clojure.java.shell shell-namespace
       'babashka.wait wait-namespace
       'babashka.signal signal-ns
       'clojure.java.io io-namespace
       'cheshire.core cheshire-core-namespace
       'clojure.data data/data-namespace
       'clojure.stacktrace stacktrace-namespace
       'clojure.zip zip-namespace
       'clojure.main {:obj clojure-main-ns
                      'demunge (sci/copy-var demunge clojure-main-ns)
                      'repl-requires (sci/copy-var clojure-main/repl-requires clojure-main-ns)
                      'repl (sci/new-var 'repl
                              (fn [& opts]
                                (let [opts (apply hash-map opts)]
                                  (repl/start-repl! @common/ctx opts))) {:ns clojure-main-ns})}
       'clojure.test t/clojure-test-namespace
       'babashka.classpath classpath-namespace
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
       'clojure.core.async async-namespace
       'clojure.core.async.impl.protocols async-protocols-namespace
       'rewrite-clj.node rewrite/node-namespace
       'rewrite-clj.paredit rewrite/paredit-namespace
       'rewrite-clj.parser rewrite/parser-namespace
       'rewrite-clj.zip rewrite/zip-namespace
       'rewrite-clj.zip.subedit rewrite/subedit-namespace}
    features/xml?  (assoc 'clojure.data.xml @(resolve 'babashka.impl.xml/xml-namespace)
                          'clojure.data.xml.event @(resolve 'babashka.impl.xml/xml-event-namespace)
                          'clojure.data.xml.tree @(resolve 'babashka.impl.xml/xml-tree-namespace))
    features/yaml? (assoc 'clj-yaml.core @(resolve 'babashka.impl.yaml/yaml-namespace)
                          'flatland.ordered.map @(resolve 'babashka.impl.ordered/ordered-map-ns))
    features/jdbc? (assoc 'next.jdbc @(resolve 'babashka.impl.jdbc/njdbc-namespace)
                          'next.jdbc.sql @(resolve 'babashka.impl.jdbc/next-sql-namespace)
                          'next.jdbc.result-set @(resolve 'babashka.impl.jdbc/result-set-namespace))
    features/csv?  (assoc 'clojure.data.csv @(resolve 'babashka.impl.csv/csv-namespace))
    features/transit? (assoc 'cognitect.transit @(resolve 'babashka.impl.transit/transit-namespace))
    features/datascript? (assoc 'datascript.core @(resolve 'babashka.impl.datascript/datascript-namespace))
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
                                @(resolve 'babashka.impl.clojure.test.check/test-check-namespace))
    features/spec-alpha? (-> (assoc        ;; spec
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
                             'clojure.tools.logging
                             @(resolve 'babashka.impl.logging/tools-logging-namespace)
                             'clojure.tools.logging.impl
                             @(resolve 'babashka.impl.logging/tools-logging-impl-namespace)
                             'clojure.tools.logging.readable
                             @(resolve 'babashka.impl.logging/tools-logging-readable-namespace))))


(def edn-readers (cond-> {}
                   features/yaml?
                   (assoc 'ordered/map @(resolve 'flatland.ordered.map/ordered-map))
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
          ("--force") (recur (next options)
                               (assoc opts-map
                                      :force? true))
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
                               :shell-out true))
          ("-O") (recur (next options)
                        (assoc opts-map
                               :edn-out true))
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
          ("-f" "--file")
          (let [options (next options)]
            (recur (next options)
                   (assoc opts-map
                          :file (first options))))
          ("--jar" "-jar")
          (let [options (next options)]
            (recur (next options)
                   (assoc opts-map
                          :jar (first options))))
          ("--repl")
          (let [options (next options)]
            (recur (next options)
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
          (let [options (next options)]
            (recur (next options)
                   (update opts-map :expressions (fnil conj []) (first options))))
          ("--main", "-m",)
          (let [options (next options)]
            (assoc opts-map :main (first options)
                   :command-line-args (rest options)))
          ("--run")
          (parse-run-opts opts-map (next options))
          ("--tasks")
          (assoc opts-map :list-tasks true
                 :command-line-args (next options))
          ("--print-deps")
          (parse-print-deps-opts opts-map (next options))
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
         "--verbose" ;; renamed to --debug
         ) (recur (next options) (assoc opts-map :debug true))
        ("--init")
        (recur (nnext options) (assoc opts-map :init (second options)))
        [options opts-map])
      [options opts-map])))

(defn parse-opts
  ([options] (parse-opts options nil))
  ([options opts-map]
   (let [[options opts-map] (if opts-map
                              [options opts-map]
                              (parse-global-opts options))
         opt (first options)
         tasks (into #{} (map str) (keys (:tasks @common/bb-edn)))]
     (if-not opt opts-map
             ;; FILE > TASK > SUBCOMMAND
             (cond
               (.isFile (io/file opt))
               (if (str/ends-with? opt ".jar")
                 (assoc opts-map
                        :jar opt
                        :command-line-args (next options))
                 (assoc opts-map
                        :file opt
                        :command-line-args (next options)))
               (contains? tasks opt)
               (assoc opts-map
                      :run opt
                      :command-line-args (next options))
               (command? opt)
               (recur (cons (str "--" opt) (next options)) opts-map)
               :else
               (parse-args options opts-map))))))

(def env (atom {}))

(defn exec [cli-opts]
  (binding [*unrestricted* true]
    (sci/binding [core/warn-on-reflection @core/warn-on-reflection
                  core/data-readers @core/data-readers
                  sci/ns @sci/ns]
      (let [{version-opt :version
             :keys [:shell-in :edn-in :shell-out :edn-out
                    :help :file :command-line-args
                    :expressions :stream? :init
                    :repl :socket-repl :nrepl
                    :debug :classpath :force?
                    :main :uberscript :describe?
                    :jar :uberjar :clojure
                    :doc :run :list-tasks
                    :print-deps]}
            cli-opts
            _ (when debug (vreset! common/debug true))
            _ (do ;; set properties
                (when main (System/setProperty "babashka.main" main))
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
            load-fn (fn [{:keys [:namespace :reload]}]
                      (when-let [{:keys [:loader]}
                                 @cp/cp-state]
                        (if ;; ignore built-in namespaces when uberscripting, unless with :reload
                            (and uberscript
                                 (not reload)
                                 (or (contains? namespaces namespace)
                                     (contains? sci-namespaces/namespaces namespace)))
                          ""
                          (let [res (cp/source-for-namespace loader namespace nil)]
                            (when uberscript (swap! uberscript-sources conj (:source res)))
                            res))))
            main (if (and jar (not main))
                   (when-let [res (cp/getResource
                                   (cp/loader jar)
                                   ["META-INF/MANIFEST.MF"] {:url? true})]
                     (cp/main-ns res))
                   main)
            ;; TODO: pull more of these values to compile time
            opts {:aliases aliases
                  :namespaces (-> namespaces
                                  (assoc 'clojure.core
                                         (assoc core-extras
                                                'load-file (sci-namespaces/core-var 'load-file load-file*))))
                  :env env
                  :features #{:bb :clj}
                  :classes classes/class-map
                  :imports classes/imports
                  :load-fn load-fn
                  :uberscript uberscript
                  ;; :readers core/data-readers
                  :reify-fn reify-fn
                  :proxy-fn proxy-fn}
            opts (addons/future opts)
            sci-ctx (sci/init opts)
            _ (vreset! common/ctx sci-ctx)
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
                                                :loader (:loader @cp/cp-state)}))))
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
                                              :loader (:loader @cp/cp-state)})))))
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
                                            :loader (:loader @cp/cp-state)}))))
                    nil))
            ;; socket REPL is start asynchronously. when no other args are
            ;; provided, a normal REPL will be started as well, which causes the
            ;; process to wait until SIGINT
            _ (when socket-repl (start-socket-repl! socket-repl sci-ctx))
            exit-code
            (or exit-code
                (second
                 (cond version-opt
                       [(print-version) 0]
                       help (print-help sci-ctx command-line-args)
                       doc (print-doc sci-ctx command-line-args)
                       describe?
                       [(print-describe) 0]
                       repl [(repl/start-repl! sci-ctx) 0]
                       nrepl [(start-nrepl! nrepl sci-ctx) 0]
                       uberjar [nil 0]
                       list-tasks [(tasks/list-tasks sci-ctx) 0]
                       print-deps [(print-deps/print-deps (:print-deps-format cli-opts)) 0]
                       expressions
                       (sci/binding [sci/file abs-path]
                         (try
                           ; when evaluating expression(s), add in repl-requires so things like
                           ; pprint and dir are available
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
                                                         (or (not run)
                                                             (:prn cli-opts)))
                                                (if-let [pr-f (cond shell-out println
                                                                    edn-out prn)]
                                                  (if (sequential? res)
                                                    (doseq [l res
                                                            :while (not (pipe-signal-received?))]
                                                      (pr-f l))
                                                    (pr-f res))
                                                  (prn res)))) 0]]
                                   (if stream?
                                     (recur)
                                     res)))))
                           (catch Throwable e
                             (error-handler e {:expression expression
                                               :debug debug
                                               :preloads preloads
                                               :loader (:loader @cp/cp-state)}))))
                       clojure [nil (if-let [proc (bdeps/clojure command-line-args)]
                                      (-> @proc :exit)
                                      0)]
                       uberscript [nil 0]
                       :else [(repl/start-repl! sci-ctx) 0]))
                1)]
        (flush)
        (when uberscript
          (let [uberscript-out uberscript]
            (spit uberscript-out "") ;; reset file
            (doseq [s (distinct @uberscript-sources)]
              (spit uberscript-out s :append true))
            (spit uberscript-out preloads :append true)
            (spit uberscript-out expression :append true)))
        (when uberjar
          (if-let [cp (cp/get-classpath)]
            (uberjar/run {:dest uberjar
                          :jar :uber
                          :classpath cp
                          :main-class main
                          :verbose debug})
            (throw (Exception. "The uberjar task needs a classpath."))))
        exit-code))))

(defn satisfies-min-version? [min-version]
  (let [[major-current minor-current patch-current] version-data
        [major-min minor-min patch-min] (parse-version min-version)]
    (or (> major-current major-min)
        (and (= major-current major-min)
             (or (> minor-current minor-min)
                 (and (= minor-current minor-min)
                      (>= patch-current patch-min)))))))

(defn main [& args]
  (let [bb-edn-file (or (System/getenv "BABASHKA_EDN")
                        "bb.edn")
        bb-edn (or (when (fs/exists? bb-edn-file)
                     (let [raw-string (slurp bb-edn-file)
                           edn (edn/read-string raw-string)
                           edn (assoc edn :raw raw-string)]
                       (vreset! common/bb-edn edn)))
                   ;; tests may have modified bb-edn
                   @common/bb-edn)
        min-bb-version (:min-bb-version bb-edn)]
    (when min-bb-version
      (when-not (satisfies-min-version? min-bb-version)
        (binding [*out* *err*]
          (println (str "WARNING: this project requires babashka "
                        min-bb-version " or newer, but you have: " version))))))
  (let [opts (parse-opts args)]
    (exec opts)))

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

;;;; Scratch

(comment)
