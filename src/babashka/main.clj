(ns babashka.main
  {:no-doc true}
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str :refer [starts-with?]]
   [me.raynes.conch :refer [execute] :as sh]
   [sci.core :as sci])
  (:gen-class))

java.lang.ProcessBuilder
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
        println? (boolean (get opts "--println"))]
    {:version version
     :raw-in raw-in
     :raw-out raw-out
     :println? println?}))

(def commands '[awk cat cd chown chmod cp diff df find grep kill ls mkdir mv
                pwd ps rm rmdir sed sort tar touch top unzip wc xargs])

(defn parse-shell-string [s]
  (str/split s #"\n"))

(def command-map
  (into {}
        (for [s commands]
          [s (fn [& args]
               (-> (apply execute (name s) args)
                   parse-shell-string))])))

(defn main
  [& args]
  (or
   (let [{:keys [:version :raw-in :raw-out :println?]} (parse-opts args)]
     (second
      (cond version
            [(println (str/trim (slurp (io/resource "BABASHKA_VERSION")))) 0]
            :else
            (let [expr (last args)
                  in (delay (let [in (slurp *in*)]
                              (if raw-in
                                (parse-shell-string in)
                                (read-edn in))))
                  [res exit-code :as ret]
                  (try [(sci/eval-string
                         expr
                         {:bindings (merge command-map
                                           {(with-meta '*in*
                                              {:sci/deref! true}) in
                                            'run! run!
                                            'sh (fn [& args]
                                                  (-> (apply execute args)
                                                      parse-shell-string))})})
                        0]
                       (catch Exception e
                         (binding [*out* *err*]
                           (println (.getMessage e)))
                         [nil 1]))]
              (when (zero? exit-code)
                (if raw-out
                  (if (coll? res)
                    (doseq [l res]
                      (println l))
                    (println res))
                  ((if println? println? prn) res)))
              ret))))
   1))

(defn -main
  [& args]
  (System/exit (apply main args)))

;;;; Scratch

(comment
  )
