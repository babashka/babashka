(ns orchard.java.classpath
  "Classpath access and modification utilities.

  Provides an alternative to the java.classpath contrib library."
  (:require
   [babashka.classpath :as bb-cp] ;; BB-PATCH read bb's classpath
   [clojure.java.io :as io]
   [orchard.misc :as misc])
  (:import
   (java.io File)
   (java.net URL URLClassLoader)
   (java.nio.file Files FileVisitOption Path)
   #_(java.util.function BiPredicate Function Predicate) ;; BB-PATCH no BiPredicate in the image
   (java.util.function Function Predicate)
   (java.util.jar JarEntry JarFile)
   (java.util.stream Collectors)))

;;; Classloaders

(defn context-classloader
  "Returns the current classloader for the current thread."
  []
  (.getContextClassLoader (Thread/currentThread)))

(defn classloaders
  "Returns the classloader hierarchy."
  ([^ClassLoader loader]
   (->> loader
        (iterate #(.getParent ^ClassLoader %))
        (take-while identity)))
  ([]
   (classloaders (context-classloader))))

(defn set-classloader!
  "Sets the current classloader for the current thread."
  [^ClassLoader loader]
  (let [thread (Thread/currentThread)]
    (.setContextClassLoader thread loader)
    loader))

;;; Classpaths

(defn system-classpath
  "Returns the URLs defined by the 'java.class.path' system property."
  []
  (map (comp io/as-url io/as-file)
       (.split (System/getProperty "java.class.path")
               (System/getProperty "path.separator"))))

(defn classpath-urls [classloader]
  (when (instance? URLClassLoader classloader)
    (seq (.getURLs ^URLClassLoader classloader))))

(defn classpath
  "Returns the URLs on the classpath."
  ([^ClassLoader loader]
   (->> (classloaders loader)
        (mapcat classpath-urls)
        (concat (system-classpath))
        (distinct)))
  ([]
   #_(classpath (context-classloader)) ;; BB-PATCH read bb's classpath
   (map (comp io/as-url io/as-file) (bb-cp/split-classpath (bb-cp/get-classpath)))))

;;; Classpath resources

(defn classpath-seq
  "Returns a sequence of all descendant non-directory files or archive entries as
  relative paths. This function is deprecated in CIDER projects because of its
  poor performance in very large projects; use `clojure-sources-on-classpath` as
  a more efficient alternative."

  ([url]
   (classpath-seq url nil))

  ([^URL url, file-seq-fn]
   (let [f (io/as-file url)]
     (cond
       (not (.exists f))
       []

       (misc/archive? url)
       (->> (enumeration-seq (.entries (JarFile. f)))
            (keep (fn [^JarEntry je]
                    (when-not (.isDirectory je) (.getName je)))))

       :else
       (->> (if file-seq-fn
              (file-seq-fn f)
              (file-seq f))
            (keep (fn [^File f]
                    (when-not (.isDirectory f)
                      (.getPath (.relativize (.toURI url) (.toURI f)))))))))))

(defn- clojure-source-file? [^String fname]
  (and #_(or (.endsWith fname ".clj") (.endsWith fname ".cljc")) ;; BB-PATCH a .bb file is a namespace too
       (or (.endsWith fname ".clj") (.endsWith fname ".cljc") (.endsWith fname ".bb"))
       ;; Exclude non-code files, configs, etc.
       (not
        (or (.startsWith fname "META-INF")
            (= fname "data_readers.clj")))))

(defn- find-clojure-source-files
  "Find .clj/.cljc files more efficiently than by using `file-seq`. Returns a list
  of filenames relative to `dir`."
  [^File dir]
  (let [start-path (.toPath dir)
        dir-uri (.toURI dir)
        max-depth 100
        matcher #_(reify BiPredicate
                  (test [_this path _attrs]
                    (clojure-source-file? (str path)))) ;; BB-PATCH no BiPredicate in the image, walk and filter instead of find
        (reify Predicate
          (test [_this path]
            (clojure-source-file? (str path))))]
    (-> #_(Files/find start-path max-depth matcher (into-array FileVisitOption []))
        (.filter (Files/walk start-path max-depth (into-array FileVisitOption [])) matcher)
        (.map (reify Function
                (apply [_this path]
                  (let [f (.toFile ^Path path)]
                    (when (.isFile f)
                      (let [rel-fname (.getPath (.relativize dir-uri (.toURI f)))]
                        ;; Double-check the relative path for exclusions.
                        (when (clojure-source-file? rel-fname)
                          rel-fname)))))))
        (.filter (reify Predicate
                   (test [_ x] (some? x))))
        (.collect (Collectors/toList)))))

(defn clojure-sources-on-classpath
  "Return a list of Clojure files (with .clj and .cljc extension) as relative
  paths that are present in the given classpath URL."
  [^URL url]
  (let [file (io/as-file url)]
    (cond
      (not (.exists file))
      []

      (misc/archive? url)
      (->> (enumeration-seq (.entries (JarFile. file)))
           (keep (fn [^JarEntry je]
                   (when-not (.isDirectory je)
                     (let [name (.getName je)]
                       (when (clojure-source-file? name)
                         name))))))

      :else
      (vec (find-clojure-source-files file)))))
