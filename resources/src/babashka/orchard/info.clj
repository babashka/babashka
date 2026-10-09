(ns orchard.info
  "Stand-in for orchard.info, for the debugger."
  (:require [clojure.java.io :as io]))

(defn file-info
  "Returns a map with the absolute path or URL of path under :file."
  [path]
  (let [res (io/resource path)]
    {:file (cond (nil? res) path
                 (= "file" (.getProtocol ^java.net.URL res)) (.getPath ^java.net.URL res)
                 :else (str res))}))
