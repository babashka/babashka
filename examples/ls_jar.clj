#!/usr/bin/env bb

;; usage: ls_jar.clj borkdude/sci 0.0.13-alpha.24

(require '[clojure.java.io :as io]
         '[clojure.string :as str])
(let [file (if (= 1 (count *command-line-args*))
             (io/file (first *command-line-args*))
             (let [lib (first *command-line-args*)
                   [_org lib-name] (str/split lib #"/")
                   version (second *command-line-args*)]
               (io/file (System/getProperty "user.home")
                        (format ".m2/repository/%s/%s/%s-%s.jar"
                                (str/replace lib "." (System/getProperty "file.separator"))
                                version
                                lib-name version))))]
  (doseq [e (enumeration-seq
             (.entries (java.util.jar.JarFile. file)))]
    (println (.getName e))))
