(ns babashka.impl.classpath
  {:no-doc true}
  (:refer-clojure :exclude [add-classpath])
  (:require [babashka.impl.clojure.main :refer [demunge]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [sci.core :as sci])
  (:import (java.io File)
           [java.util.jar JarFile Manifest]
           (java.net URL)))

(set! *warn-on-reflection* true)

(defprotocol IResourceResolver
  (getResource [this paths opts])
  (getResources [this paths opts]))

(deftype DirectoryResolver [path]
  IResourceResolver
  (getResource [_ resource-paths url?]
    (some
     (fn [resource-path]
       (let [f (io/file path resource-path)]
         (when (.exists f)
           (if url?
             ;; manual conversion, faster than going through .toURI
             (URL. "file" nil (.getAbsolutePath f))
             {:file (.getAbsolutePath f)
              :source (slurp f)}))))
     resource-paths)))

(defn path-from-jar
  [^File jar-file resource-paths url?]
  (with-open [jar (JarFile. jar-file)]
    (some (fn [path]
            (when-let [entry (.getEntry jar path)]
              (if url?
                ;; manual conversion, faster than going through .toURI
                (URL. "jar" nil
                      (str "file:" (.getAbsolutePath jar-file) "!/" path))
                {:file path
                 :source (slurp (.getInputStream jar entry))})))
          resource-paths)))

(deftype JarFileResolver [jar-file]
  IResourceResolver
  (getResource [_ resource-paths url?]
    (path-from-jar jar-file resource-paths url?)))

(defn part->entry [part]
  (when-not (str/blank? part)
    (if (str/ends-with? part ".jar")
      (JarFileResolver. (io/file part))
      (DirectoryResolver. (io/file part)))))

(deftype Loader [entries]
  IResourceResolver
  (getResource [_ resource-paths opts]
    (some #(getResource % resource-paths opts) entries))
  (getResources [_ resource-paths opts]
    (keep #(getResource % resource-paths opts) entries)))

(def path-sep (System/getProperty "path.separator"))

(defn classpath-entries [^String classpath]
  (let [parts (.split classpath path-sep)]
    (keep part->entry parts)))

(defn loader [^String classpath]
    (Loader. (classpath-entries classpath)))

(declare get-classpath)

(defn source-for-namespace
  ([namespace opts]
   (some-> (get-classpath) loader (source-for-namespace namespace opts)))
  ([loader namespace opts]
   (let [ns-str (name namespace)
         ^String ns-str (munge ns-str)
         ;; do NOT pick the platform specific file separator here, since that doesn't work for searching in .jar files
         ;; (io/file "foo" "bar/baz") does work on Windows, despite the forward slash
         base-path (.replace ns-str "." "/")
         manifest-paths (loop [ns (str/split ns-str #"\.")
                               paths []]
                          (let [path (str/join "/" (conj ns "pod-manifest.edn"))
                                next-ns (-> ns butlast vec)
                                next-paths (conj paths path)]
                            (if (< 1 (count next-ns)) ; don't look in top-level (e.g. com, pod) namespaces
                              (recur next-ns next-paths)
                              next-paths)))
         resource-paths   (into (mapv #(str base-path %)
                                      [".bb" ".clj" ".cljc"])
                                manifest-paths)]
     (getResource loader resource-paths opts))))

(defn main-ns [manifest-resource]
  (with-open [is (io/input-stream manifest-resource)]
    (some-> (Manifest. is)
            (.getMainAttributes)
            (.getValue "Main-Class")
            (demunge))))

(def cp-state (atom nil))

(defn add-classpath
  "Adds extra-classpath, a string as for example returned by clojure
  -Spath, to the current classpath."
  [extra-classpath]
  (swap! cp-state
         (fn [{:keys [:cp]}]
           (let [new-cp
                 (if-not cp extra-classpath
                         (str cp path-sep extra-classpath))]
             {:loader (loader new-cp)
              :cp new-cp})))
  nil)

(defn split-classpath
  "Returns the classpath as a seq of strings, split by the platform
  specific path separator."
  ([^String cp] (vec (.split cp path-sep))))

(defn get-classpath
  "Returns the current classpath as set by --classpath, BABASHKA_CLASSPATH and add-classpath."
  []
  (:cp @cp-state))

(defn resource
  (^URL [path] (when-let [st @cp-state] (resource (:loader st) path)))
  (^URL [loader path]
   (if (str/starts-with? path "/") nil ;; non-relative paths always return nil
       (getResource loader [path] true))))

(def cns (sci/create-ns 'babashka.classpath nil))

(def classpath-namespace
  {:obj cns
   'add-classpath (sci/copy-var add-classpath cns)
   'split-classpath (sci/copy-var split-classpath cns)
   'get-classpath (sci/copy-var get-classpath cns)})

;;;; Scratch

(comment
  (def cp "src:feature-xml:feature-core-async:feature-yaml:feature-csv:feature-transit:feature-java-time:feature-java-nio:sci/src:babashka.curl/src:babashka.pods/src:resources:sci/resources:/Users/borkdude/.m2/repository/com/cognitect/transit-java/1.0.343/transit-java-1.0.343.jar:/Users/borkdude/.m2/repository/org/clojure/clojure/1.10.2-alpha1/clojure-1.10.2-alpha1.jar:/Users/borkdude/.m2/repository/commons-codec/commons-codec/1.10/commons-codec-1.10.jar:/Users/borkdude/.m2/repository/org/clojure/tools.analyzer/1.0.0/tools.analyzer-1.0.0.jar:/Users/borkdude/.m2/repository/org/clojure/tools.logging/0.6.0/tools.logging-0.6.0.jar:/Users/borkdude/.m2/repository/org/clojure/core.specs.alpha/0.2.44/core.specs.alpha-0.2.44.jar:/Users/borkdude/.m2/repository/org/clojure/spec.alpha/0.2.187/spec.alpha-0.2.187.jar:/Users/borkdude/.m2/repository/org/clojure/tools.cli/1.0.194/tools.cli-1.0.194.jar:/Users/borkdude/.m2/repository/org/clojure/tools.analyzer.jvm/1.0.0/tools.analyzer.jvm-1.0.0.jar:/Users/borkdude/.m2/repository/borkdude/graal.locking/0.0.2/graal.locking-0.0.2.jar:/Users/borkdude/.m2/repository/com/fasterxml/jackson/dataformat/jackson-dataformat-cbor/2.10.2/jackson-dataformat-cbor-2.10.2.jar:/Users/borkdude/.m2/repository/com/googlecode/json-simple/json-simple/1.1.1/json-simple-1.1.1.jar:/Users/borkdude/.m2/repository/org/flatland/ordered/1.5.9/ordered-1.5.9.jar:/Users/borkdude/.m2/repository/org/postgresql/postgresql/42.2.12/postgresql-42.2.12.jar:/Users/borkdude/.m2/repository/fipp/fipp/0.6.22/fipp-0.6.22.jar:/Users/borkdude/.m2/repository/com/fasterxml/jackson/core/jackson-core/2.10.2/jackson-core-2.10.2.jar:/Users/borkdude/.m2/repository/org/yaml/snakeyaml/1.25/snakeyaml-1.25.jar:/Users/borkdude/.m2/repository/org/ow2/asm/asm/5.2/asm-5.2.jar:/Users/borkdude/.gitlibs/libs/clj-commons/conch/9aa7724e925cb8bf163e0b62486dd420b84e5f0b/src:/Users/borkdude/.m2/repository/org/javassist/javassist/3.18.1-GA/javassist-3.18.1-GA.jar:/Users/borkdude/.m2/repository/seancorfield/next.jdbc/1.0.424/next.jdbc-1.0.424.jar:/Users/borkdude/.m2/repository/org/clojure/data.xml/0.2.0-alpha6/data.xml-0.2.0-alpha6.jar:/Users/borkdude/.m2/repository/org/msgpack/msgpack/0.6.12/msgpack-0.6.12.jar:/Users/borkdude/.m2/repository/borkdude/edamame/0.0.11-alpha.9/edamame-0.0.11-alpha.9.jar:/Users/borkdude/.m2/repository/org/clojure/data.csv/1.0.0/data.csv-1.0.0.jar:/Users/borkdude/.m2/repository/com/cognitect/transit-clj/1.0.324/transit-clj-1.0.324.jar:/Users/borkdude/.m2/repository/clj-commons/clj-yaml/0.7.1/clj-yaml-0.7.1.jar:/Users/borkdude/.m2/repository/org/clojure/core.rrb-vector/0.1.1/core.rrb-vector-0.1.1.jar:/Users/borkdude/.m2/repository/persistent-sorted-set/persistent-sorted-set/0.1.2/persistent-sorted-set-0.1.2.jar:/Users/borkdude/.m2/repository/cheshire/cheshire/5.10.0/cheshire-5.10.0.jar:/Users/borkdude/.m2/repository/tigris/tigris/0.1.2/tigris-0.1.2.jar:/Users/borkdude/.m2/repository/org/clojure/tools.reader/1.3.2/tools.reader-1.3.2.jar:/Users/borkdude/.m2/repository/datascript/datascript/0.18.11/datascript-0.18.11.jar:/Users/borkdude/.m2/repository/org/hsqldb/hsqldb/2.4.0/hsqldb-2.4.0.jar:/Users/borkdude/.m2/repository/org/clojure/core.memoize/0.8.2/core.memoize-0.8.2.jar:/Users/borkdude/.m2/repository/org/clojure/data.priority-map/0.0.7/data.priority-map-0.0.7.jar:/Users/borkdude/.m2/repository/org/clojure/java.data/1.0.64/java.data-1.0.64.jar:/Users/borkdude/.m2/repository/borkdude/sci.impl.reflector/0.0.1/sci.impl.reflector-0.0.1.jar:/Users/borkdude/.m2/repository/nrepl/bencode/1.1.0/bencode-1.1.0.jar:/Users/borkdude/.m2/repository/org/clojure/core.cache/0.8.2/core.cache-0.8.2.jar:/Users/borkdude/.m2/repository/org/clojure/core.async/1.1.587/core.async-1.1.587.jar:/Users/borkdude/.m2/repository/com/fasterxml/jackson/dataformat/jackson-dataformat-smile/2.10.2/jackson-dataformat-smile-2.10.2.jar:/Users/borkdude/.m2/repository/org/clojure/data.codec/0.1.0/data.codec-0.1.0.jar:/Users/borkdude/.m2/repository/javax/xml/bind/jaxb-api/2.3.0/jaxb-api-2.3.0.jar")
  (def l (loader cp))
  (source-for-namespace l 'babashka.impl.cheshire nil)
  (time (:file (source-for-namespace l 'cheshire.core nil)))) ;; 20ms -> 2.25ms

