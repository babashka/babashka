#!/usr/bin/env bb

;; usage: ls_jar.clj borkdude/sci 0.0.13-alpha.24

(require '[clojure.java.io :as io]
         '[clojure.string :as str])

(let [lib (first *command-line-args*)
      [_org lib-name] (str/split lib #"/")
      version (second *command-line-args*)]
  (doseq [e (enumeration-seq
             (.entries (java.util.jar.JarFile.
                        (io/file (System/getProperty "user.home")
                                 (format ".m2/repository/%s/%s/%s-%s.jar"
                                         lib version
                                         lib-name version)))))]
    (println (.getName e))))
