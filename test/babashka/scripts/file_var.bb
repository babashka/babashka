(ns file-var
  (:require [clojure.java.io :as io]))

(require '[file-var-classpath])
(load-file (io/file "test" "babashka" "scripts" "loaded_by_file_var.bb"))
(println *file*)
(defn foo [])
(println (:file (meta #'foo)))
