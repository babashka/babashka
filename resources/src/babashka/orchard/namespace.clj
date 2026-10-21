(ns orchard.namespace
  "Utilities for resolving and loading namespaces."
  {:author "Jeff Valk"
   :added "0.5"}
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [orchard.java.classpath :as cp]
   [orchard.misc :as misc]
   [orchard.util.io :as util.io])
  (:import
   (java.net URL)))

;;; Namespace/source resolution

(defn- resource-name->ns-name
  "Infer the namespace name from the classpath resource name."
  [^String resource-name]
  (.. (subs resource-name 0 (.lastIndexOf resource-name "."))
      (replace "/" ".")
      (replace "_" "-")))

(defn canonical-source
  "Returns the URL of the source file for the namespace object or symbol,
  according to the canonical naming convention, if present on the classpath."
  ^URL [ns]
  (let [path (-> (str ns)
                 (str/replace "-" "_")
                 (str/replace "." "/"))]
    (or (io/resource (str path ".clj"))
        (io/resource (str path ".cljc"))
        (io/resource (str path ".cljs")))))

;;; Namespace Loading

(defn ensure-namespace!
  "Require `ns` (no-op if already loaded). Return the symbol if successful,
  and `nil` if this fails."
  [ns]
  (try (doto (symbol ns) require)
       (catch Exception _)))

;;; Filters

(defn inlined-dependency?
  "Returns true if the namespace matches one of our, or eastwood's,
   inlined dependencies."
  [namespace]
  (let [ns-name (str (ns-name namespace))]
    (or
     ;; rewritten by mranderson
     (.startsWith ns-name "deps.")
     (.startsWith ns-name "mranderson")
     (.startsWith ns-name "cider.nrepl.inlined.deps")
     (.contains ns-name ".inlined-deps.")
     ;; rewritten by dolly
     (.startsWith ns-name "eastwood.copieddeps"))))

(defn internal-namespace?
  "Returns true if the namespace matches the given prefixes."
  [namespace & [prefixes]]
  (let [ns-name (str (ns-name namespace))]
    (->> prefixes
         (map re-pattern)
         (map #(re-find % ns-name))
         (some (complement nil?)))))

(defn has-tests?
  "Returns a truthy value if the namespace has any vars with `:test` metadata."
  [ns]
  (some (comp :test meta val) (ns-interns ns)))

;;; Project Namespaces
;;
;; These methods search sources on the classpath. Non-classpath source
;; files, documentation code, etc within the project directory are ignored.

(defn jvm-clojure-resource-name->ns-name
  "Given a .clj or .cljc `resource-name`, returns its namespace name."
  [resource-name]
  ;; io/resource can return nil for Emacs backup files, for example
  (when (io/resource resource-name)
    (symbol (resource-name->ns-name resource-name))))

(defn classpath-namespaces
  "Returns all namespaces (as identified by files with .clj or .cljc extension)
  defined in sources on the classpath or the specified classpath URLs."
  ([] (classpath-namespaces (cp/classpath)))
  ([classpath-urls]
   (->> classpath-urls
        (mapcat cp/clojure-sources-on-classpath)
        (keep jvm-clojure-resource-name->ns-name)
        distinct
        sort)))

(defn project-namespaces
  "Returns all JVM Clojure namespaces defined in sources within the current project."
  []
  (->> (cp/classpath)
       (filter #(and (misc/directory? %)
                     (util.io/file-in-project? (.getFile ^URL %))))
       (classpath-namespaces)))

(defn loaded-project-namespaces
  "Return all loaded namespaces defined in the current project."
  []
  (->> (project-namespaces)
       (filterv (set (map ns-name (all-ns))))))

(defn load-project-namespaces
  "Require and return all namespaces validly defined in the current project."
  []
  (into [] (keep ensure-namespace!) (project-namespaces)))

(defn loaded-namespaces
  "Returns all loaded namespaces, except those coming from inlined dependencies.
  `filter-regexps` is used to filter out namespaces matching regexps."
  [& [filter-regexps]]
  (->> (all-ns)
       (remove #(or (inlined-dependency? %)
                    (internal-namespace? % filter-regexps)))
       (map (comp name ns-name))
       (sort)))
