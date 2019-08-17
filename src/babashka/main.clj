(ns babashka.main
  {:no-doc true}
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [sci.core :as sci])
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
                   ("--help") {:help? true}
                   ("--stream") (recur (rest options)
                                       (assoc opts-map
                                              :stream? true))
                   ("--time") (recur (rest options)
                                     (assoc opts-map
                                            :time? true))
                   ("-i") (recur (rest options)
                                 (assoc opts-map
                                        :raw-in true))
                   ("-o") (recur (rest options)
                                 (assoc opts-map
                                        :raw-out true))
                   ("-io") (recur (rest options)
                                  (assoc opts-map
                                         :raw-in true
                                         :raw-out true))
                   ("-f" "--file")
                   (let [options (rest options)]
                     (recur (rest options)
                            (assoc opts-map
                                   :file (first options))))
                   (if (not (:file opts-map))
                     (assoc opts-map
                            :expression opt
                            :command-line-args (rest options))
                     (assoc opts-map
                            :command-line-args options)))
                 opts-map))]
    opts))

(defn parse-shell-string [s]
  (str/split s #"\n"))

(defn print-version []
  (println (str "babashka v"(str/trim (slurp (io/resource "BABASHKA_VERSION"))))))

(def usage-string "Usage: bb [ --help ] | [ --version ] | ( [ -i ] [ -o ] | [ -io ] ) [ --stream ] ( expression | -f <file> )")
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
  --help: print this help text.
  --version: print the current version of babashka.

  -i: read shell input into a list of strings instead of reading EDN.
  -o: write shell output instead of EDN.
  -io: combination of -i and -o.
  --stream: stream over lines or EDN values from stdin. Combined with -i *in* becomes a single line per iteration.
  --file or -f: read expressions from file instead of argument wrapped in an implicit do.
"))

(defn read-file [file]
  (let [f (io/file file)]
    (if (.exists f)
      (as-> (slurp file) x
        ;; remove shebang
        (str/replace x #"^#!.*" "")
        (format "(do %s)" x))
      (throw (Exception. (str "File does not exist: " file))))))

(defn get-env
  ([] (System/getenv))
  ([s] (System/getenv s)))

(defn get-property
  ([s]
   (System/getProperty s))
  ([s d]
   (System/getProperty s d)))

(defn set-property [k v]
  (System/setProperty k v))

(defn get-properties []
  (System/getProperties))

(defn exit [n]
  (throw (ex-info "" {:bb/exit-code n})))

(def bindings
  {'run! run!
   'shell/sh shell/sh
   'csh shell/sh ;; backwards compatibility, deprecated
   'slurp slurp
   'spit spit
   'pmap pmap
   'print print
   'pr-str pr-str
   'prn prn
   'println println
   'edn/read-string edn/read-string
   'System/getenv get-env
   'System/getProperty get-property
   'System/setProperty set-property
   'System/getProperties get-properties
   'System/exit exit})

(defn read-edn []
  (edn/read {;;:readers *data-readers*
             :eof ::EOF} *in*))

#_(defn set-ssl []
  (let [home (System/getProperty "user.home")
        bb-lib-dir (io/file home ".babashka" "lib")
        lib-path (System/getProperty "java.library.path")
        ca-certs-dir (io/file bb-lib-dir "security")
        ca-certs (.getPath (io/file ca-certs-dir "cacerts"))]
    (System/setProperty "java.library.path" (str (.getPath  bb-lib-dir) ":" lib-path))
    (System/setProperty "javax.net.ssl.trustStore" ca-certs)
    (System/setProperty "javax.net.ssl.tru stAnchors" ca-certs)))

(defn load-file* [ctx file]
  (let [s (slurp file)]
    (sci/eval-string s ctx)))

(defn main
  [& args]
  #_(binding [*out* *err*]
      (prn ">> args" args))
  (let [t0 (System/currentTimeMillis)
        {:keys [:version :raw-in :raw-out :println?
                :help? :file :command-line-args
                :expression :stream? :time?] :as _opts}
        (parse-opts args)
        env (atom {})
        exit-code
        (or
         #_(binding [*out* *err*]
             (prn ">>" _opts))
         (second
          (cond version
                [(print-version) 0]
                help?
                [(print-help) 0]
                :else
                (try

                  (let [expr (if file (read-file file) (format "(do %s)" expression))
                        read-next #(if stream?
                                     (if raw-in (or (read-line) ::EOF)
                                         (read-edn))
                                     (delay (let [in (slurp *in*)]
                                              (if raw-in
                                                (parse-shell-string in)
                                                (edn/read-string in)))))]
                    (loop [in (read-next)]
                      (let [ctx {:bindings (assoc bindings
                                                  (with-meta '*in*
                                                    (when-not stream? {:sci/deref! true})) in
                                                  #_(with-meta 'bb/*in*
                                                      {:sci/deref! true}) #_do-in
                                                  '*command-line-args* command-line-args)
                                 :env env}
                            ctx (update ctx :bindings assoc 'load-file #(load-file* ctx %))]
                        (if (identical? ::EOF in)
                          [nil 0] ;; done streaming
                          (let [res [(do (when-not (or expression file)
                                           (throw (Exception. (str args  "Babashka expected an expression. Type --help to print help."))))
                                         (let [res (sci/eval-string
                                                    expr
                                                    ctx)]
                                           (if raw-out
                                             (if (coll? res)
                                               (doseq [l res]
                                                 (println l))
                                               (println res))
                                             ((if println? println? prn) res)))) 0]]
                            (if stream?
                              (recur (read-next))
                              res))))))
                  (catch Exception e
                    (binding [*out* *err*]
                      (let [d (ex-data e)
                            exit-code (:bb/exit-code d)]
                        (if exit-code [nil exit-code]
                            (do (when-let [msg (or (:stderr d )
                                                  (.getMessage e))]
                                  (println (str/trim msg)))
                                [nil 1]))))))))
         1)
        t1 (System/currentTimeMillis)]
    (when time? (binding [*out* *err*]
                  (println "bb took" (str (- t1 t0) "ms."))))
    exit-code))

(defn -main
  [& args]
  (let [exit-code (apply main args)]
    (System/exit exit-code)))

;;;; Scratch

(comment
  )
