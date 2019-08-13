(ns babashka.main
  {:no-doc true}
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.java.shell :as cjs]
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
        help? (boolean (get opts "--help"))]
    {:version version
     :raw-in raw-in
     :raw-out raw-out
     :println? println?
     :help? help?}))

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
"))

(defn main
  [& args]
  (or
   (let [{:keys [:version :raw-in :raw-out :println?
                 :help?]} (parse-opts args)]
     (second
      (cond version
            [(print-version) 0]
            help?
            [(print-help) 0]
            :else
            (try
              [(let [exprs (drop-while #(str/starts-with? % "-") args)
                     _ (when (not= (count exprs) 1)
                         (throw (Exception. ^String usage-string)))
                     expr (last args)
                     in (delay (let [in (slurp *in*)]
                                 (if raw-in
                                   (parse-shell-string in)
                                   (read-edn in))))
                     res (sci/eval-string
                          expr
                          {:bindings {(with-meta '*in*
                                        {:sci/deref! true}) in
                                      'run! run!
                                      'csh cjs/sh}})]
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
