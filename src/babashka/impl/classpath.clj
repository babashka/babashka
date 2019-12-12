(ns babashka.impl.classpath
  {:no-doc true}
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.util.jar JarFile JarFile$JarFileEntry]))

(set! *warn-on-reflection* true)

(defprotocol IResourceResolver
  (getResource [this path]))

(deftype DirectoryResolver [path]
  IResourceResolver
  (getResource [this resource-path]
    (let [f (io/file path resource-path)]
      (when (.exists f)
        (slurp f)))))

(defn path-from-jar
  [^java.io.File jar-file path]
  (with-open [jar (JarFile. jar-file)]
    (let [entries (enumeration-seq (.entries jar))
          entry (some (fn [^JarFile$JarFileEntry x]
                        (let [nm (.getName x)]
                          (when (and (not (.isDirectory x)) (= path nm))
                            (slurp (.getInputStream jar x))))) entries)]
      entry)))

(deftype JarFileResolver [path]
  IResourceResolver
  (getResource [this resource-path]
    (path-from-jar path resource-path)))

(defn part->entry [part]
  (if (str/ends-with? part ".jar")
    (JarFileResolver. (io/file part))
    (DirectoryResolver. (io/file part))))

(deftype Loader [entries]
  IResourceResolver
  (getResource [this resource-path]
    (some #(getResource % resource-path) entries)))

(defn loader [^String classpath]
  (let [parts (.split classpath (System/getProperty "path.separator"))
        entries (map part->entry parts)]
    (Loader. entries)))

(defn source-for-namespace [loader namespace]
  (let [ns-str (name namespace)
        ^String ns-str (munge ns-str)
        path (.replace ns-str "." (System/getProperty "file.separator"))
        paths (map #(str path %) [".bb" ".clj" ".cljc"])]
    (some #(getResource loader %) paths)))

;;;; Scratch

(comment
  (def l (loader "src:/Users/borkdude/.m2/repository/cheshire/cheshire/5.9.0/cheshire-5.9.0.jar"))
  (source-for-namespace l 'babashka.impl.cheshire)
  (source-for-namespace l 'cheshire.core)
  )
