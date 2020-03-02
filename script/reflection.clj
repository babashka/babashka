#!/usr/bin/env bb

(require '[clojure.java.io :as io] '[clojure.string :as str] '[clojure.java.shell :refer [sh]])
(def version (str/trim (slurp (io/file "resources" "BABASHKA_VERSION"))))
(sh "lein" "with-profiles" "+reflection" "run")
(io/copy (io/file "reflection.json") (io/file (str "babashka-" version "-reflection.json")))
