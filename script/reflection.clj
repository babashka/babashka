#!/usr/bin/env bb

(require '[clojure.java.io :as io]
         '[clojure.java.shell :refer [sh]]
         '[clojure.string :as str])

(def version (str/trim (slurp (io/file "resources" "BABASHKA_VERSION"))))
(sh "lein" "with-profiles" "+reflection" "run")
(io/copy (io/file "reflection.json") (io/file (str "babashka-" version "-reflection.json")))
