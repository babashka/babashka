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

(defn read-edn [s]
  (edn/read-string
   {:readers *data-readers*}
   s))

(defn- parse-opts [options]
  (let [opts (loop [options options
                    opts-map {}]
               (if-let [opt (first options)]
                 (case opt
                   ("--version") {:version true}
                   ("--help") {:help? true}
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

(def usage-string "Usage: bb [ --help ] | [ --version ] | ( [ -i ] [ -o ] | [ -io ] ) ( expression | -f <file> )")
(defn print-usage []
  (println usage-string))

(defn print-help []
  (println (str "babashka v" (str/trim (slurp (io/resource "BABASHKA_VERSION")))))
  (println (str "sci v" (str/trim (slurp (io/resource "SCI_VERSION")))))
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
  --file or -f: read expression from file instead of argument
"))

(defn read-file [file]
  (let [f (io/file file)]
    (if (.exists f)
      (as-> (slurp file) x
        ;; remove hashbang
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

(defn get-properties []
  (System/getProperties))

(defn exit [n]
  (System/exit n))

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
   'System/getProperties get-properties
   'System/exit exit})

(defn main
  [& args]
  #_(binding [*out* *err*]
    (prn ">> args" args))
  (or
   (let [{:keys [:version :raw-in :raw-out :println?
                 :help? :file :command-line-args
                 :expression] :as _opts} (parse-opts args)]
     #_(binding [*out* *err*]
       (prn ">>" _opts))
     (second
      (cond version
            [(print-version) 0]
            help?
            [(print-help) 0]
            :else
            (try
              [(do (when-not (or expression file)
                     (throw (Exception. "Missing expression.")))
                 (let [expr (if file (read-file file) expression)
                       do-in (delay (slurp *in*))
                       in (delay (let [in @do-in]
                                   (if raw-in
                                     (parse-shell-string in)
                                     (read-edn in))))
                       res (sci/eval-string
                            expr
                            {:bindings (assoc bindings
                                              (with-meta '*in*
                                                {:sci/deref! true}) in
                                              (with-meta 'bb/*in*
                                                {:sci/deref! true}) do-in
                                              '*command-line-args* command-line-args)})]
                   (if raw-out
                     (if (coll? res)
                       (doseq [l res]
                         (println l))
                       (println res))
                     ((if println? println? prn) res)))) 0]
              (catch Exception e
                (binding [*out* *err*]
                  (when-let [msg (or (:stderr (ex-data e))
                                     (.getMessage e))]
                    (println (str/trim msg) )))
                [nil 1])))))
   1))

(defn -main
  [& args]
  (System/exit (apply main args)))

;;;; Scratch

(comment
  )
