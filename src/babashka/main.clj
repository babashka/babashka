(ns babashka.main
  {:no-doc true}
  (:require
   [babashka.impl.async :refer [async-namespace async-protocols-namespace]]
   [babashka.impl.cheshire :refer [cheshire-core-namespace]]
   [babashka.impl.classes :as classes]
   [babashka.impl.classpath :as cp]
   [babashka.impl.clojure.core :refer [core-extras]]
   [babashka.impl.clojure.java.io :refer [io-namespace]]
   [babashka.impl.clojure.java.shell :refer [shell-namespace]]
   [babashka.impl.clojure.main :refer [demunge]]
   [babashka.impl.clojure.pprint :refer [pprint-namespace]]
   [babashka.impl.clojure.stacktrace :refer [stacktrace-namespace]]
   [babashka.impl.common :as common]
   [babashka.impl.csv :as csv]
   [babashka.impl.curl :refer [curl-namespace]]
   [babashka.impl.pipe-signal-handler :refer [handle-pipe! pipe-signal-received?]]
   [babashka.impl.repl :as repl]
   [babashka.impl.socket-repl :as socket-repl]
   [babashka.impl.test :as t]
   [babashka.impl.tools.cli :refer [tools-cli-namespace]]
   [babashka.impl.transit :refer [transit-namespace]]
   [babashka.wait :as wait]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.stacktrace :refer [print-stack-trace]]
   [clojure.string :as str]
   [sci.addons :as addons]
   [sci.core :as sci]
   [sci.impl.interpreter :refer [eval-string*]]
   [sci.impl.opts :as sci-opts]
   [sci.impl.types :as sci-types]
   [sci.impl.unrestrict :refer [*unrestricted*]]
   [sci.impl.vars :as vars]
   [babashka.impl.nrepl-server :as nrepl-server])
  (:gen-class))

(binding [*unrestricted* true]
  (sci/alter-var-root sci/in (constantly *in*))
  (sci/alter-var-root sci/out (constantly *out*))
  (sci/alter-var-root sci/err (constantly *err*)))

(set! *warn-on-reflection* true)
;; To detect problems when generating the image, run:
;; echo '1' | java -agentlib:native-image-agent=config-output-dir=/tmp -jar target/babashka-xxx-standalone.jar '...'
;; with the java provided by GraalVM.

(defn parse-opts [options]
  (let [opts (loop [options options
                    opts-map {}]
               (if options
                 (let [opt (first options)]
                   (case opt
                     ("--") (assoc opts-map :command-line-args (next options))
                     ("--version") {:version true}
                     ("--help" "-h" "-?") {:help? true}
                     ("--verbose")(recur (next options)
                                         (assoc opts-map
                                                :verbose? true))
                     ("--stream") (recur (next options)
                                         (assoc opts-map
                                                :stream? true))
                     ("--time") (recur (next options)
                                       (assoc opts-map
                                              :time? true))
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
                     ("-f" "--file")
                     (let [options (next options)]
                       (recur (next options)
                              (assoc opts-map
                                     :file (first options))))
                     ("--repl")
                     (let [options (next options)]
                       (recur (next options)
                              (assoc opts-map
                                     :repl true)))
                     ("--socket-repl")
                     (let [options (next options)]
                       (recur (next options)
                              (assoc opts-map
                                     :socket-repl (or (first options)
                                                      "1666"))))
                     ("--nrepl")
                     (let [options (next options)]
                       (recur (next options)
                              (assoc opts-map
                                     :nrepl (or (first options)
                                                      "1667"))))
                     ("--eval", "-e")
                     (let [options (next options)]
                       (recur (next options)
                              (update opts-map :expressions (fnil conj []) (first options))))
                     ("--main", "-m")
                     (let [options (next options)]
                       (recur (next options)
                              (assoc opts-map :main (first options))))
                     (if (some opts-map [:file :socket-repl :expressions :main])
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
                                  :file opt
                                  :command-line-args (next options)))))))
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
          [ ( --classpath | -cp ) <cp> ] [ --uberscript <file> ]
          [ ( --main | -m ) <main-namespace> | -e <expression> | -f <file> |
            --repl | --socket-repl [<host>:]<port> ]
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
  --help, -h or -?    Print this help text.
  --version           Print the current version of babashka.

  -i                  Bind *input* to a lazy seq of lines from stdin.
  -I                  Bind *input* to a lazy seq of EDN values from stdin.
  -o                  Write lines to stdout.
  -O                  Write EDN values to stdout.
  --verbose           Print entire stacktrace in case of exception.
  --stream            Stream over lines or EDN values from stdin. Combined with -i or -I *input* becomes a single value per iteration.
  --uberscript <file> Collect preloads, -e, -f and -m and all required namespaces from the classpath into a single executable file.

  -e, --eval <expr>   Evaluate an expression.
  -f, --file <path>   Evaluate a file.
  -cp, --classpath    Classpath to use.
  -m, --main <ns>     Call the -main function from namespace with args.
  --repl              Start REPL. Use rlwrap for history.
  --socket-repl       Start socket REPL. Specify port (e.g. 1666) or host and port separated by colon (e.g. 127.0.0.1:1666).
  --time              Print execution time before exiting.
  --                  Stop parsing args and pass everything after -- to *command-line-args*

If neither -e, -f, or --socket-repl are specified, then the first argument that is not parsed as a option is treated as a file if it exists, or as an expression otherwise.
Everything after that is bound to *command-line-args*."))

(defn read-file [file]
  (let [f (io/file file)]
    (if (.exists f)
      (as-> (slurp file) x
        ;; remove shebang
        (str/replace x #"^#!.*" ""))
      (throw (Exception. (str "File does not exist: " file))))))

(def reflection-var (sci/new-dynamic-var '*warn-on-reflection* false))

(defn load-file* [sci-ctx f]
  (let [f (io/file f)
        s (slurp f)
        prev-ns @vars/current-ns]
    (sci/with-bindings {vars/current-file (.getCanonicalPath f)}
      (try
        (eval-string* sci-ctx s)
        (finally (sci-types/setVal vars/current-ns prev-ns))))))

(defn start-socket-repl! [address ctx]
  (socket-repl/start-repl! address ctx)
  ;; hang until SIGINT
  @(promise))

(defn start-nrepl! [address ctx]
  (nrepl-server/start-server! ctx address)
  ;; hang until SIGINT
  #_@(promise))

(defn exit [n]
  (throw (ex-info "" {:bb/exit-code n})))

(def aliases
  '{tools.cli clojure.tools.cli
    edn clojure.edn
    wait babashka.wait
    signal babashka.signal
    shell clojure.java.shell
    io clojure.java.io
    async clojure.core.async
    csv clojure.data.csv
    json cheshire.core
    curl babashka.curl
    transit cognitect.transit})

(def cp-state (atom nil))

(defn add-classpath* [add-to-cp]
  (swap! cp-state
         (fn [{:keys [:cp]}]
           (let [new-cp
                 (if-not cp add-to-cp
                         (str cp (System/getProperty "path.separator") add-to-cp))]
             {:loader (cp/loader new-cp)
              :cp new-cp})))
  nil)

(def namespaces
  {'clojure.tools.cli tools-cli-namespace
   'clojure.java.shell shell-namespace
   'babashka.wait {'wait-for-port wait/wait-for-port
                   'wait-for-path wait/wait-for-path}
   'babashka.signal {'pipe-signal-received? pipe-signal-received?}
   'clojure.java.io io-namespace
   'clojure.core.async async-namespace
   'clojure.core.async.impl.protocols async-protocols-namespace
   'clojure.data.csv csv/csv-namespace
   'cheshire.core cheshire-core-namespace
   'clojure.stacktrace stacktrace-namespace
   'clojure.main {'demunge demunge}
   'clojure.repl {'demunge demunge}
   'clojure.test t/clojure-test-namespace
   'babashka.classpath {'add-classpath add-classpath*}
   'clojure.pprint pprint-namespace
   'babashka.curl curl-namespace
   'cognitect.transit transit-namespace})

(def bindings
  {'java.lang.System/exit exit ;; override exit, so we have more control
   'System/exit exit})

(defn error-handler* [^Exception e verbose?]
  (binding [*out* *err*]
    (let [d (ex-data e)
          exit-code (:bb/exit-code d)]
      (if exit-code [nil exit-code]
          (do (if verbose?
                (print-stack-trace e)
                (println (str (.. e getClass getName)
                              (when-let [m (.getMessage e)]
                                (str ": " m)) )))
              (flush)
              [nil 1])))))

(defn main
  [& args]
  (handle-pipe!)
  #_(binding [*out* *err*]
      (prn "M" (meta (get bindings 'future))))
  (binding [*unrestricted* true]
    (sci/binding [reflection-var false
                  vars/current-ns (vars/->SciNamespace 'user nil)]
      (let [t0 (System/currentTimeMillis)
            {:keys [:version :shell-in :edn-in :shell-out :edn-out
                    :help? :file :command-line-args
                    :expressions :stream? :time?
                    :repl :socket-repl :nrepl
                    :verbose? :classpath
                    :main :uberscript] :as _opts}
            (parse-opts args)
            read-next (fn [*in*]
                        (if (pipe-signal-received?)
                          ::EOF
                          (if stream?
                            (if shell-in (or (read-line) ::EOF)
                                (edn/read {;;:readers *data-readers*
                                           :eof ::EOF} *in*))
                            (delay (cond shell-in
                                         (shell-seq *in*)
                                         edn-in
                                         (edn-seq *in*)
                                         :else
                                         (edn/read *in*))))))
            uberscript-sources (atom ())
            env (atom {})
            classpath (or classpath
                          (System/getenv "BABASHKA_CLASSPATH"))
            _ (when classpath
                (add-classpath* classpath))
            load-fn (fn [{:keys [:namespace]}]
                      (when-let [{:keys [:loader]} @cp-state]
                        (let [res (cp/source-for-namespace loader namespace nil)]
                          (when uberscript (swap! uberscript-sources conj (:source res)))
                          res)))
            _ (when file (vars/bindRoot vars/current-file (.getCanonicalPath (io/file file))))
            ctx {:aliases aliases
                 :namespaces (-> namespaces
                                 (assoc 'clojure.core
                                        (assoc core-extras
                                               '*command-line-args*
                                               (sci/new-dynamic-var '*command-line-args* command-line-args)
                                               '*warn-on-reflection* reflection-var))
                                 (assoc-in ['clojure.java.io 'resource]
                                           #(when-let [{:keys [:loader]} @cp-state] (cp/getResource loader % {:url? true}))))
                 :bindings bindings
                 :env env
                 :features #{:bb :clj}
                 :classes classes/class-map
                 :imports '{ArithmeticException java.lang.ArithmeticException
                            AssertionError java.lang.AssertionError
                            Boolean java.lang.Boolean
                            Class java.lang.Class
                            Double java.lang.Double
                            Exception java.lang.Exception
                            IllegalArgumentException java.lang.IllegalArgumentException
                            Integer java.lang.Integer
                            File java.io.File
                            Long java.lang.Long
                            Math java.lang.Math
                            NumberFormatException java.lang.NumberFormatException
                            Object java.lang.Object
                            RuntimeException java.lang.RuntimeException
                            ProcessBuilder java.lang.ProcessBuilder
                            String java.lang.String
                            StringBuilder java.lang.StringBuilder
                            System java.lang.System
                            Thread java.lang.Thread
                            Throwable java.lang.Throwable}
                 :load-fn load-fn
                 :dry-run uberscript}
            ctx (addons/future ctx)
            sci-ctx (sci-opts/init ctx)
            _ (vreset! common/ctx sci-ctx)
            input-var (sci/new-dynamic-var '*input* nil)
            _ (swap! (:env sci-ctx)
                     (fn [env]
                       (update env :namespaces
                               (fn [namespaces] [:namespaces 'clojure.main 'repl]
                                 (-> namespaces
                                     (assoc-in ['clojure.core 'load-file] #(load-file* sci-ctx %))
                                     (assoc-in ['clojure.main 'repl]
                                               (fn [& opts]
                                                 (let [opts (apply hash-map opts)]
                                                   (repl/start-repl! sci-ctx opts))))
                                     (assoc-in ['user (with-meta '*input*
                                                        (when-not stream?
                                                          {:sci.impl/deref! true}))] input-var))))))
            preloads (some-> (System/getenv "BABASHKA_PRELOADS") (str/trim))
            [expressions exit-code]
            (cond expressions [expressions nil]
                  main [[(format "(ns user (:require [%1$s])) (apply %1$s/-main *command-line-args*)"
                                 main)] nil]
                  file (try [[(read-file file)] nil]
                            (catch Exception e
                              (error-handler* e verbose?))))
            expression (str/join " " expressions) ;; this might mess with the locations...
            exit-code
            ;; handle preloads
            (if exit-code exit-code
                (do (when preloads
                      (try
                        (eval-string* sci-ctx preloads)
                        (catch Throwable e
                          (error-handler* e verbose?))))
                    nil))
            exit-code
            (or exit-code
                (second
                 (cond version
                       [(print-version) 0]
                       help?
                       [(print-help) 0]
                       repl [(repl/start-repl! sci-ctx) 0]
                       socket-repl [(start-socket-repl! socket-repl sci-ctx) 0]
                       nrepl [(start-nrepl! nrepl sci-ctx) 0]
                       (not (str/blank? expression))
                       (try
                         (loop []
                           (let [in (read-next *in*)]
                             (if (identical? ::EOF in)
                               [nil 0] ;; done streaming
                               (let [res [(let [res
                                                (sci/binding [input-var in]
                                                  (eval-string* sci-ctx expression))]
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
                                   (recur)
                                   res)))))
                         (catch Throwable e
                           (error-handler* e verbose?)))
                       uberscript [nil 0]
                       :else [(repl/start-repl! sci-ctx) 0]))
                1)
            t1 (System/currentTimeMillis)]
        (flush)
        (when uberscript
          uberscript
          (let [uberscript-out uberscript]
            (spit uberscript-out "") ;; reset file
            (doseq [s @uberscript-sources]
              (spit uberscript-out s :append true))
            (spit uberscript-out preloads :append true)
            (spit uberscript-out expression :append true)))
        (when time? (binding [*out* *err*]
                      (println "bb took" (str (- t1 t0) "ms."))))
        exit-code))))

(defn -main
  [& args]
  (if-let [dev-opts (System/getenv "BABASHKA_DEV")]
    (let [{:keys [:n]} (edn/read-string dev-opts)
          last-iteration (dec n)]
      (dotimes [i n]
        (if (< i last-iteration)
          (with-out-str (apply main args))
          (do (apply main args)
              (binding [*out* *err*]
                (println "ran" n "times"))))))
    (let [exit-code (apply main args)]
      (System/exit exit-code))))

;;;; Scratch

(comment
  )
