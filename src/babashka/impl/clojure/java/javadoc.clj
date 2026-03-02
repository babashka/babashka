(ns babashka.impl.clojure.java.javadoc
  {:no-doc true}
  (:require [babashka.impl.clojure.java.browse :as browse]
            [clojure.string :as str]
            [sci.core :as sci])
  (:import (java.io File)))

(set! *warn-on-reflection* true)

(def jns (sci/create-ns 'clojure.java.javadoc nil))

(def local-javadocs (sci/new-dynamic-var '*local-javadocs* (ref (list)) {:ns jns}))

(def core-java-api
  (let [java-version (System/getProperty "java.specification.version")]
    (str "https://docs.oracle.com/en/java/javase/" java-version "/docs/api/%s/")))

(def remote-javadocs
  (sci/new-dynamic-var '*remote-javadocs*
                       (ref (sorted-map
                             "java." core-java-api
                             "javax." core-java-api
                             "org.ietf.jgss." core-java-api
                             "org.omg." core-java-api
                             "org.w3c.dom." core-java-api
                             "org.xml.sax." core-java-api))
                       {:ns jns}))

(defn add-local-javadoc [path]
  (dosync (commute @local-javadocs conj path)))

(defn add-remote-javadoc [package-prefix url]
  (dosync (commute @remote-javadocs assoc package-prefix url)))

(defn- fill-in-module-name [^String url ^String classname]
  (if (.contains url "%s")
    (let [klass (Class/forName classname)
          module-name (.getName (.getModule klass))]
      (format url module-name))
    url))

(defn javadoc-url
  "Searches for a URL for the given class name. Tries
  *local-javadocs* first, then *remote-javadocs*. Returns a string."
  [^String classname]
  (let [classname (str/replace classname #"\$.*" "")
        file-path (.replace classname \. File/separatorChar)
        url-path (.replace classname \. \/)]
    (if-let [file ^File (first
                         (filter #(.exists ^File %)
                                 (map #(File. (str %) (str file-path ".html"))
                                      @@local-javadocs)))]
      (-> file .toURI str)
      (some (fn [[prefix url]]
              (when (.startsWith classname prefix)
                (str (fill-in-module-name url classname)
                     url-path ".html")))
            @@remote-javadocs))))

(defn javadoc [class-or-object]
  (let [^Class c (if (instance? Class class-or-object)
                   class-or-object
                   (class class-or-object))]
    (if-let [url (javadoc-url (.getName c))]
      (browse/browse-url url)
      (println "Could not find Javadoc for" c))))

(def javadoc-namespace
  {'*local-javadocs*    local-javadocs
   '*remote-javadocs*   remote-javadocs
   '*core-java-api*     (sci/new-var '*core-java-api* core-java-api {:ns jns})
   'add-local-javadoc   (sci/copy-var add-local-javadoc jns)
   'add-remote-javadoc  (sci/copy-var add-remote-javadoc jns)
   'javadoc-url         (sci/copy-var javadoc-url jns)
   'javadoc             (sci/copy-var javadoc jns)})
