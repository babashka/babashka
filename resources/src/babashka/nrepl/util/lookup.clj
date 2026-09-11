(ns nrepl.util.lookup
  "Symbol info lookup.

  It's meant to provide you with useful data like definition location,
  parameter lists, etc.

  NOTE: The functionality here is experimental and
  the API is subject to changes."
  {:author "Bozhidar Batsov"
   :added "0.8"}
  (:require
   [nrepl.misc :as misc]))

(defn special-sym-meta
  [sym]
  ;; clojure.repl/special-doc is private, so we need to work a
  ;; bit to be able to invoke it
  (let [f (misc/requiring-resolve 'clojure.repl/special-doc)]
    (assoc (f sym)
           :ns "clojure.core"
           :file "clojure/core.clj"
           :special-form "true")))

(defn normal-sym-meta
  [ns sym]
  (some-> (ns-resolve ns sym) meta))

(defn sym-meta
  [ns sym]
  (if (special-symbol? sym)
    (special-sym-meta sym)
    (normal-sym-meta ns sym)))

(defn lookup
  "Lookup the metadata for `sym`.
  If the `sym` is not qualified then it will be resolved in the context
  of `ns`."
  [ns sym]
  (some-> (sym-meta ns sym) misc/sanitize-meta))
