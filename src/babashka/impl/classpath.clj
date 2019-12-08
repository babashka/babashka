(ns babashka.impl.classpath
  {:no-doc true}
  (:require [clojure.java.io :as io]))

(set! *warn-on-reflection* true)

(defn file->url [f]
  (-> (io/file f) .toURL))

(defn classpath->classloader [^String classpath]
  (let [parts (.split classpath (System/getProperty "path.separator"))
        urls (map file->url parts)
        urls (into-array java.net.URL urls)]
    (java.net.URLClassLoader. urls)))

(defn get-resource-as-string [^java.net.URLClassLoader classloader path]
  (when-let [is (.getResourceAsStream classloader path)]
    (slurp is)))

(defn source-for-namespace [^java.net.URLClassLoader classloader namespace]
  (let [ns-str (name namespace)
        path (.replace ns-str "." "/")
        paths (map #(str path %) [".clj" ".cljc"])]
    (some #(get-resource-as-string classloader %) paths)))

;;;; Scratch

(comment
  (def cl (classpath->classloader ".:file:///Users/borkdude/.m2/repository/cheshire/cheshire/5.9.0/cheshire-5.9.0.jar"))
  (source-for-namespace cl 'cheshire.core)
  )
