(ns orchard.info
  "Stand-in for orchard.info, used by the debugger."
  (:require [clojure.java.io :as io]))

(defn file-info
  "Returns a map with the URL of path under :file.
  path is a classpath-relative or absolute file path.
  Returns path itself under :file if it is neither on the classpath nor a file."
  [path]
  {:file (or (some-> (io/resource path) str)
             (let [f (io/file path)]
               (when (.exists f) (str (.toURI f))))
             path)})
