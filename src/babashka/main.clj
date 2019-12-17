(ns babashka.main
  {:no-doc true}
  (:require
   [babashka.impl.async :refer [async-namespace]]
   [babashka.impl.cheshire :refer [cheshire-core-namespace]]
   [babashka.impl.classes :as classes]
   [babashka.impl.classpath :as cp]
   [babashka.impl.clojure.core :refer [core-extras]]
   [babashka.impl.clojure.java.io :refer [io-namespace]]
   [babashka.impl.clojure.stacktrace :refer [print-stack-trace]]
   [babashka.impl.csv :as csv]
   [babashka.impl.pipe-signal-handler :refer [handle-pipe! pipe-signal-received?]]
   [babashka.impl.repl :as repl]
   [babashka.impl.socket-repl :as socket-repl]
   [babashka.impl.tools.cli :refer [tools-cli-namespace]]
   [babashka.impl.utils :refer [eval-string]]
   [babashka.wait :as wait]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [sci.addons :as addons])
  (:gen-class))

(set! *warn-on-reflection* true)
;; To detect problems when generating the image, run:
;; echo '1' | java -agentlib:native-image-agent=config-output-dir=/tmp -jar target/babashka-xxx-standalone.jar '...'
;; with the java provided by GraalVM.

(defn parse-opts [options]
  (let [opts (loop [options options
                    opts-map {}]
               (if-let [opt (first options)]
                 (case opt
                   ("--version") {:version true}
                   ("--help" "-h" "-?") {:help? true}
                   ("--verbose")(recur (rest options)
                                       (assoc opts-map
                                              :verbose? true))
                   ("--stream") (recur (rest options)
                                       (assoc opts-map
                                              :stream? true))
                   ("--time") (recur (rest options)
                                     (assoc opts-map
                                            :time? true))
                   ("-i") (recur (rest options)
                                 (assoc opts-map
                                        :shell-in true))
                   ("-I") (recur (rest options)
                                 (assoc opts-map
                                        :edn-in true))
                   ("-o") (recur (rest options)
                                 (assoc opts-map
                                        :shell-out true))
                   ("-O") (recur (rest options)
                                 (assoc opts-map
                                        :edn-out true))
                   ("-io") (recur (rest options)
                                  (assoc opts-map
                                         :shell-in true
                                         :shell-out true))
                   ("-IO") (recur (rest options)
                                  (assoc opts-map
                                         :edn-in true
                                         :edn-out true))
                   ("-f" "--file")
                   (let [options (rest options)]
                     (recur (rest options)
                            (assoc opts-map
                                   :file (first options))))
                   ("--repl")
                   (let [options (rest options)]
                     (recur (rest options)
                            (assoc opts-map
                                   :repl true)))
                   ("--socket-repl")
                   (let [options (rest options)]
                     (recur (rest options)
                            (assoc opts-map
                                   :socket-repl (first options))))
                   ("--eval", "-e")
                   (let [options (rest options)]
                     (recur (rest options)
                            (assoc opts-map :expression (first options))))
                   ("--classpath", "-cp")
                   (let [options (rest options)]
                     (recur (rest options)
                            (assoc opts-map :classpath (first options))))
                   ("--main", "-m")
                   (let [options (rest options)]
                     (recur (rest options)
                            (assoc opts-map :main (first options))))
                   (if (some opts-map [:file :socket-repl :expression :main])
                     (assoc opts-map
                            :command-line-args options)
                     (if (and (not= \( (first (str/trim opt)))
                              (.exists (io/file opt)))
                       (assoc opts-map
                              :file opt
                              :command-line-args (rest options))
                       (assoc opts-map
                              :expression opt
                              :command-line-args (rest options)))))
                 opts-map))]
    opts))

(defn edn-seq*
  [^java.io.BufferedReader rdr]
  (let [edn-val (edn/read {:eof ::EOF} rdr)]
    (when (not (identical? ::EOF edn-val))
      (cons edn-val (lazy-seq (edn-seq* rdr))))))

(defn edn-seq
  [in]
  (edn-seq* in))

(defn shell-seq [in]
  (line-seq (java.io.BufferedReader. in)))

(defn print-version []
  (println (str "babashka v"(str/trim (slurp (io/resource "BABASHKA_VERSION"))))))

(def usage-string "Usage: bb [ -i | -I ] [ -o | -O ] [ --stream ] [--verbose]
          [ ( --classpath | -cp ) <cp> ] [ ( --main | -m ) <main-namespace> ]
          ( -e <expression> | -f <file> | --repl | --socket-repl [<host>:]<port> )
          [ arg* ]")
(defn print-usage []
  (println usage-string))

(defn print-help []
  (println (str "Babashka v" (str/trim (slurp (io/resource "BABASHKA_VERSION")))))
  ;; (println (str "sci v" (str/trim (slurp (io/resource "SCI_VERSION")))))
  (println)
  (print-usage)
  (println)
  (println "Options:")
  (println "
  --help, -h or -?   Print this help text.
  --version          Print the current version of babashka.
  -i                 Bind *in* to a lazy seq of lines from stdin.
  -I                 Bind *in* to a lazy seq of EDN values from stdin.
  -o                 Write lines to stdout.
  -O                 Write EDN values to stdout.
  --verbose          Print entire stacktrace in case of exception.
  --stream           Stream over lines or EDN values from stdin. Combined with -i or -I *in* becomes a single value per iteration.
  -e, --eval <expr>  Evaluate an expression.
  -f, --file <path>  Evaluate a file.
  -cp, --classpath   Classpath to use.
  -m, --main <ns>    Call the -main function from namespace with args.
  --repl             Start REPL
  --socket-repl      Start socket REPL. Specify port (e.g. 1666) or host and port separated by colon (e.g. 127.0.0.1:1666).
  --time             Print execution time before exiting.

If neither -e, -f, or --socket-repl are specified, then the first argument that is not parsed as a option is treated as a file if it exists, or as an expression otherwise.
Everything after that is bound to *command-line-args*."))

(defn read-file [file]
  (let [f (io/file file)]
    (if (.exists f)
      (as-> (slurp file) x
        ;; remove shebang
        (str/replace x #"^#!.*" ""))
      (throw (Exception. (str "File does not exist: " file))))))

(defn read-edn []
  (edn/read {;;:readers *data-readers*
             :eof ::EOF} *in*))

(defn load-file* [ctx file]
  (let [s (slurp file)]
    (eval-string s ctx)))

(defn eval* [ctx form]
  (eval-string (pr-str form) ctx))

(defn start-repl! [ctx read-next]
  (let [ctx (update ctx :bindings assoc
                    (with-meta '*in*
                      {:sci/deref! true})
                    (read-next))]
    (repl/start-repl! ctx)))

(defn start-socket-repl! [address ctx read-next]
  (let [ctx (update ctx :bindings assoc
                    (with-meta '*in*
                      {:sci/deref! true})
                    (read-next))]
    (socket-repl/start-repl! address ctx)
    ;; hang until SIGINT
    @(promise)))

(defn exit [n]
  (throw (ex-info "" {:bb/exit-code n})))

;; (sci/set-var-root! sci/*in* *in*)
;; (sci/set-var-root! sci/*out* *out*)
;; (sci/set-var-root! sci/*err* *err*)

(defn main
  [& args]
  (handle-pipe!)
  #_(binding [*out* *err*]
      (prn "M" (meta (get bindings 'future))))
  (let [t0 (System/currentTimeMillis)
        {:keys [:version :shell-in :edn-in :shell-out :edn-out
                :help? :file :command-line-args
                :expression :stream? :time?
                :repl :socket-repl
                :verbose? :classpath
                :main] :as _opts}
        (parse-opts args)
        read-next (fn [*in*]
                    (if (pipe-signal-received?)
                      ::EOF
                      (if stream?
                        (if shell-in (or (read-line) ::EOF)
                            (read-edn))
                        (delay (cond shell-in
                                     (shell-seq *in*)
                                     edn-in
                                     (edn-seq *in*)
                                     :else
                                     (edn/read *in*))))))
        env (atom {})
        classpath (or classpath
                      (System/getenv "BABASHKA_CLASSPATH"))
        loader (when classpath
                 (cp/loader classpath))
        load-fn (when classpath
                  (fn [{:keys [:namespace]}]
                    (cp/source-for-namespace loader namespace)))
        ctx {:aliases '{tools.cli 'clojure.tools.cli
                        edn clojure.edn
                        wait babashka.wait
                        sig babashka.signal
                        shell clojure.java.shell
                        io clojure.java.io
                        async clojure.core.async
                        csv clojure.data.csv
                        json cheshire.core}
             :namespaces {'clojure.core (assoc core-extras
                                               '*command-line-args* command-line-args)
                          'clojure.tools.cli tools-cli-namespace
                          'clojure.edn {'read-string edn/read-string}
                          'clojure.java.shell {'sh shell/sh}
                          'babashka.wait {'wait-for-port wait/wait-for-port
                                          'wait-for-path wait/wait-for-path}
                          'babashka.signal {'pipe-signal-received? pipe-signal-received?}
                          'clojure.java.io io-namespace
                          'clojure.core.async async-namespace
                          'clojure.data.csv csv/csv-namespace
                          'cheshire.core cheshire-core-namespace}
             :bindings {'java.lang.System/exit exit ;; override exit, so we have more control
                        'System/exit exit}
             :env env
             :features #{:bb}
             :classes classes/class-map
             :imports '{ArithmeticException java.lang.ArithmeticException
                        AssertionError java.lang.AssertionError
                        Boolean java.lang.Boolean
                        Class java.lang.Class
                        Double java.lang.Double
                        Exception java.lang.Exception
                        Integer java.lang.Integer
                        File java.io.File
                        ProcessBuilder java.lang.ProcessBuilder
                        String java.lang.String
                        System java.lang.System
                        Thread java.lang.Thread}
             :load-fn load-fn}
        ctx (update ctx :bindings assoc 'eval #(eval* ctx %)
                    'load-file #(load-file* ctx %))
        ctx (addons/future ctx)
        _preloads (some-> (System/getenv "BABASHKA_PRELOADS") (str/trim) (eval-string ctx))
        expression (if main
                     (format "(ns user (:require [%1$s])) (apply %1$s/-main *command-line-args*)"
                             main)
                     expression)
        exit-code
        (or
         #_(binding [*out* *err*]
             (prn ">>" _opts))
         (second
          (cond version
                [(print-version) 0]
                help?
                [(print-help) 0]
                repl [(start-repl! ctx #(read-next *in*)) 0]
                socket-repl [(start-socket-repl! socket-repl ctx #(read-next *in*)) 0]
                :else
                (try
                  (let [expr (if file (read-file file) expression)]
                    (if expr
                      (loop [in (read-next *in*)]
                        (let [ctx (update-in ctx [:namespaces 'user] assoc (with-meta '*in*
                                                                             (when-not stream?
                                                                               {:sci/deref! true})) in)]
                          (if (identical? ::EOF in)
                            [nil 0] ;; done streaming
                            (let [res [(let [res (eval-string expr ctx)]
                                         (when (some? res)
                                           (if-let [pr-f (cond shell-out println
                                                               edn-out prn)]
                                             (if (coll? res)
                                               (doseq [l res
                                                       :while (not (pipe-signal-received?))]
                                                 (pr-f l))
                                               (pr-f res))
                                             (prn res)))) 0]]
                              (if stream?
                                (recur (read-next *in*))
                                res)))))
                      [(start-repl! ctx #(read-next *in*)) 0]))
                  (catch Throwable e
                    (binding [*out* *err*]
                      (let [d (ex-data e)
                            exit-code (:bb/exit-code d)]
                        (if exit-code [nil exit-code]
                            (do (if verbose?
                                  (print-stack-trace e)
                                  (println (.getMessage e)))
                                (flush)
                                [nil 1]))))))))
         1)
        t1 (System/currentTimeMillis)]
    (when time? (binding [*out* *err*]
                  (println "bb took" (str (- t1 t0) "ms."))))
    (flush)
    exit-code))

(defn -main
  [& args]
  (let [exit-code (apply main args)]
    (System/exit exit-code)))

;;;; Scratch

(comment
  )
