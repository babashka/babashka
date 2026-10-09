(ns orchard.info
  "Stand-in for orchard.info, for the debugger."
  (:require [clojure.java.io :as io]))

(defn file-info
  "Returns a map with the URL of path under :file.
  path is a classpath-relative or absolute file path.
  Returns path itself under :file if neither exists."
  [path]
  {:file (or (some-> (io/resource path) str)
             (let [f (io/file path)]
               (when (.exists f) (str (.toURI f))))
             path)})
