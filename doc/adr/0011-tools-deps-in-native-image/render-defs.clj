#!/usr/bin/env bb
;; Render target/tools-deps/precise.edn as the two defs classes.clj carries.
(require '[clojure.pprint :as pp]
         '[clojure.string :as str])

(let [lines (str/split-lines (slurp "target/tools-deps/precise.edn"))
      precise (read-string (nth lines 1))
      name-only (read-string (nth lines 3))]
  (with-out-str)
  (spit "target/tools-deps/precise-defs.clj"
        (with-out-str
          (print "(def tools-deps-methods\n  (quote ")
          (pp/pprint precise)
          (println "))")
          (println)
          (print "(def tools-deps-name-only\n  (quote ")
          (pp/pprint name-only)
          (println "))")))
  (println "rendered"))
