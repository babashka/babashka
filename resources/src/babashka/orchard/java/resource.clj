(ns orchard.java.resource
  "Resolve JVM resource-related information."
  {:added "0.5"}
  (:require
   [clojure.java.io :as io]
   [orchard.java.classpath :as cp])
  (:import (java.io File)
           (java.net URL)))

(defn project-resources
  "Get a list of classpath resources, i.e. files that are not clojure/java source
  or class files. Only consider classpath entries that are directories, does not
  consider jars."
  []
  (->> (cp/classpath (cp/context-classloader))
       (map io/as-file)
       (filter (memfn ^File isDirectory))
       (mapcat
        (fn [^File directory]
          (->> (file-seq directory)
               (filter (memfn ^File isFile))
               (map (fn [^File file]
                      (let [relpath (.getPath
                                     (.relativize (.toURI directory)
                                                  (.toURI file)))]
                        {:root directory
                         :file file
                         :relpath relpath
                         :url (io/as-url file)})))
               (remove #(.startsWith ^String (:relpath %) "META-INF/"))
               (remove #(re-matches #".*\.(clj[cs]?|java|class)" (:relpath %))))))))

(defn resource-full-path ^URL [relative-path]
  (io/resource relative-path (cp/context-classloader)))

(defn resource-path-tuple
  "If it's a resource, return a tuple of the relative path and the full
  resource path."
  [path]
  (or (when-let [full (resource-full-path path)]
        [path full])
      (when-let [[_ relative] (re-find #".*jar!/(.*)" path)]
        (when-let [full (resource-full-path relative)]
          [relative full]))
      ;; handles load-file on jar resources from a cider buffer
      (when-let [[_ relative] (re-find #".*jar:(.*)" path)]
        (when-let [full (resource-full-path relative)]
          [relative full]))))

(defn resource-path
  "Return the resource path for the given name."
  {:added "0.5"}
  [name]
  (some-> name (resource-full-path) (.getPath)))

(defn resource-maps
  "Return a seq of resource maps:

    {:file    \"the absolute path to the resource\"
     :relpath \"the path of the resource relative to the classpath\"}

  If the project does not contain resources, it returns nil."
  {:added "0.5"}
  []
  (map #(select-keys % [:file :relpath])
       (project-resources)))
