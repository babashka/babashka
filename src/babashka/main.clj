(ns babashka.main
  {:no-doc true}
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str :refer [starts-with?]]
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
                    opts-map {}
                    current-opt nil]
               (if-let [opt (first options)]
                 (if (starts-with? opt "-")
                   (recur (rest options)
                          (assoc opts-map opt [])
                          opt)
                   (recur (rest options)
                          (update opts-map current-opt conj opt)
                          current-opt))
                 opts-map))
        version (boolean (get opts "--version"))
        raw-in (boolean (or (get opts "--raw")
                            (get opts "-i")
                            (get opts "-io")))
        raw-out (boolean (or (get opts "-o")
                             (get opts "-io")))
        println? (boolean (get opts "--println"))
        help? (boolean (get opts "--help"))
        file (first (or (get opts "-f")
                        (get opts "--file")))]
    {:version version
     :raw-in raw-in
     :raw-out raw-out
     :println? println?
     :help? help?
     :file file}))

(defn parse-shell-string [s]
  (str/split s #"\n"))

(defn print-version []
  (println (str "babashka v"(str/trim (slurp (io/resource "BABASHKA_VERSION"))))))

(def usage-string "Usage: [ --help ] [ -i ] [ -o ] [ -io ] [ --version ] [ expression ]")
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
  (as-> (slurp file) x
    ;; remove hashbang
    (str/replace x #"^#!.*" "")
    (format "(do %s)" x)))

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

(def bindings
  {'run! run!
   'shell/sh shell/sh
   'csh shell/sh ;; backwards compatibility, deprecated
   'pmap pmap
   'print print
   'pr-str pr-str
   'prn prn
   'println println
   'System/getenv get-env
   'System/getProperty get-property
   'System/getProperties get-properties})

(defn main
  [& args]
  (or
   (let [{:keys [:version :raw-in :raw-out :println?
                 :help? :file]} (parse-opts args)]
     (second
      (cond version
            [(print-version) 0]
            help?
            [(print-help) 0]
            :else
            (try
              [(let [exprs (drop-while #(str/starts-with? % "-") args)
                     _ (when-not (or (= 1 (count exprs)) file)
                         (throw (Exception. ^String usage-string)))
                     expr (if file (read-file file) (last args))
                     in (delay (let [in (slurp *in*)]
                                 (if raw-in
                                   (parse-shell-string in)
                                   (read-edn in))))
                     res (sci/eval-string
                          expr
                          {:bindings (assoc bindings
                                            (with-meta '*in*
                                              {:sci/deref! true}) in)})]
                 (if raw-out
                   (if (coll? res)
                     (doseq [l res]
                       (println l))
                     (println res))
                   ((if println? println? prn) res))) 0]
              (catch Exception e
                (binding [*out* *err*]
                  (println (str/trim
                            (or (:stderr (ex-data e))
                                (.getMessage e))) ))
                [nil 1])))))
   1))

(defn -main
  [& args]
  (System/exit (apply main args)))

;;;; Scratch

(comment
  )
