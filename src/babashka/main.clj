(ns babashka.main
  {:no-doc true}
  (:require
   [babashka.impl.File :refer [file-bindings]]
   [babashka.impl.System :refer [system-bindings]]
   [babashka.impl.Thread :refer [thread-bindings]]
   [babashka.impl.async :refer [async-bindings]]
   [babashka.impl.clojure.core :refer [core-bindings]]
   [babashka.impl.clojure.stacktrace :refer [print-stack-trace]]
   [babashka.impl.conch :refer [conch-bindings]]
   [babashka.impl.pipe-signal-handler :refer [handle-pipe! pipe-signal-received?]]
   [babashka.impl.socket-repl :as socket-repl]
   [babashka.net :as net]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [sci.core :as sci])
  (:import [sun.misc Signal]
           [sun.misc SignalHandler])
  (:gen-class))

(set! *warn-on-reflection* true)
;; To detect problems when generating the image, run:
;; echo '1' | java -agentlib:native-image-agent=config-output-dir=/tmp -jar target/babashka-xxx-standalone.jar '...'
;; with the java provided by GraalVM.

(defn- parse-opts [options]
  (let [opts (loop [options options
                    opts-map {}]
               (if-let [opt (first options)]
                 (case opt
                   ("--version") {:version true}
                   ("--help" "-h" "-?") {:help? true}
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
                   ("--socket-repl")
                   (let [options (rest options)]
                     (recur (rest options)
                            (assoc opts-map
                                   :socket-repl (first options))))
                   (if (not (or (:file opts-map)
                                (:socket-repl opts-map)))
                     (assoc opts-map
                            :expression opt
                            :command-line-args (rest options))
                     (assoc opts-map
                            :command-line-args options)))
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

(def usage-string "Usage: bb [ -i | -I ] [ -o | -O ] [ --stream ] ( expression | -f <file> | --socket-repl [host:]port )")
(defn print-usage []
  (println usage-string))

(defn print-help []
  (println (str "babashka v" (str/trim (slurp (io/resource "BABASHKA_VERSION")))))
  ;; (println (str "sci v" (str/trim (slurp (io/resource "SCI_VERSION")))))
  (println)
  (print-usage)
  (println)
  (println "Options:")
  (println "
  --help, -h or -?: print this help text.
  --version: print the current version of babashka.

  -i: bind *in* to a lazy seq of lines from stdin.
  -I: bind *in* to a lazy seq of EDN values from stdin.
  -o: write lines to stdout.
  -O: write EDN values to stdout.
  --stream: stream over lines or EDN values from stdin. Combined with -i or -I *in* becomes a single value per iteration.
  --file or -f: read expressions from file instead of argument wrapped in an implicit do.
  --socket-repl: start socket REPL. Specify port (e.g. 1666) or host and port separated by colon (e.g. 127.0.0.1:1666).
  --time: print execution time before exiting.
"))

(defn read-file [file]
  (let [f (io/file file)]
    (if (.exists f)
      (as-> (slurp file) x
        ;; remove shebang
        (str/replace x #"^#!.*" ""))
      (throw (Exception. (str "File does not exist: " file))))))

(def bindings
  (merge {'shell/sh shell/sh
          'namespace namespace
          ;; clojure.java.io
          'io/as-relative-path io/as-relative-path
          'io/copy io/copy
          'io/delete-file io/delete-file
          'io/file io/file
          'io/reader io/reader
          'edn/read-string edn/read-string
          'net/wait-for-it net/wait-for-it
          'sig/pipe-signal-received? pipe-signal-received?}
         core-bindings
         system-bindings
         file-bindings
         thread-bindings
         conch-bindings
         async-bindings))

(defn read-edn []
  (edn/read {;;:readers *data-readers*
             :eof ::EOF} *in*))

(defn load-file* [ctx file]
  (let [s (slurp file)]
    (sci/eval-string s ctx)))

(defn start-socket-repl! [address ctx read-next]
  (let [ctx (update ctx :bindings assoc
                    (with-meta '*in*
                      {:sci/deref! true})
                    (read-next))]
    (socket-repl/start-repl! address ctx)
    ;; hang until SIGINT
    @(promise)))

(defn main
  [& args]
  (handle-pipe!)
  #_(binding [*out* *err*]
    (prn "M" (meta (get bindings 'future))))
  (let [t0 (System/currentTimeMillis)
        {:keys [:version :shell-in :edn-in :shell-out :edn-out
                :help? :file :command-line-args
                :expression :stream? :time? :socket-repl] :as _opts}
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
        ctx {:bindings (assoc bindings '*command-line-args* command-line-args)
             :env env}
        ctx (update ctx :bindings assoc 'load-file #(load-file* ctx %))
        _preloads (some-> (System/getenv "BABASHKA_PRELOADS") (str/trim) (sci/eval-string ctx))
        exit-code
        (or
         #_(binding [*out* *err*]
             (prn ">>" _opts))
         (second
          (cond version
                [(print-version) 0]
                help?
                [(print-help) 0]
                socket-repl [(start-socket-repl! socket-repl ctx #(read-next *in*)) 0]
                :else
                (try
                  (let [expr (if file (read-file file) expression)]
                    (loop [in (read-next *in*)]
                      (let [ctx (update ctx :bindings assoc (with-meta '*in*
                                                              (when-not stream?
                                                                {:sci/deref! true})) in)]
                        (if (identical? ::EOF in)
                          [nil 0] ;; done streaming
                          (let [res [(do (when-not (or expression file)
                                           (throw (Exception. (str args  "Babashka expected an expression. Type --help to print help."))))
                                         (let [res (sci/eval-string expr ctx)]
                                           (if (some? res)
                                             (if-let [pr-f (cond shell-out println
                                                                 edn-out prn)]
                                               (if (coll? res)
                                                 (doseq [l res
                                                         :while (not (pipe-signal-received?))]
                                                   (pr-f l))
                                                 (pr-f res))
                                               (prn res))))) 0]]
                            (if stream?
                              (recur (read-next *in*))
                              res))))))
                  (catch Throwable e
                    (binding [*out* *err*]
                      (let [d (ex-data e)
                            exit-code (:bb/exit-code d)]
                        (if exit-code [nil exit-code]
                            (do (print-stack-trace e)
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
