(ns babashka.impl.clojure.tools.reader
  (:refer-clojure :exclude [read])
  (:require
   [edamame.core :as e]
   [sci.core :as sci]
   [sci.ctx-store :as ctx]
   [sci.impl.parser :as p]))

(def rns (sci/create-ns 'clojure.tools.reader))

(def default-opts
  (e/normalize-opts
   {:all true
    :row-key :line
    :col-key :column
    :location? seq?
    :end-location false}))

(def default-data-reader-fn (sci/new-dynamic-var '*default-data-reader-fn* nil {:ns rns}))
(def alias-map (sci/new-dynamic-var '*alias-map* nil {:ns rns}))

(defn resolve-tag [sym]
  ;; https://github.com/clojure/tools.reader/blob/ff18b1b872398a99e3e2941a0ed9abc0c2dec151/src/main/clojure/clojure/tools/reader.clj#L858
  (or (default-data-readers sym)
      (when-let [f @default-data-reader-fn]
        (f sym))))

;; Added for compatibility with tools.namespace
(defn read
  "Reads the first object from an IPushbackReader or a java.io.PushbackReader.
   Returns the object read. If EOF, throws if eof-error? is true.
   Otherwise returns sentinel. If no stream is provided, *in* will be used.
   Opts is a persistent map with valid keys:
    :read-cond - :allow to process reader conditionals, or
                 :preserve to keep all branches
    :features - persistent set of feature keywords for reader conditionals
    :eof - on eof, return value unless :eofthrow, then throw.
           if not specified, will throw
   ***WARNING***
   Note that read can execute code (controlled by *read-eval*),
   and as such should be used only with trusted sources.
   To read data structures only, use clojure.tools.reader.edn/read
   Note that the function signature of clojure.tools.reader/read and
   clojure.tools.reader.edn/read is not the same for eof-handling"
  {:arglists '([] [reader] [opts reader] [reader eof-error? eof-value])}
  ([] (read @sci/in true nil))
  ([reader] (read reader true nil))
  ([{eof :eof :as opts :or {eof :eofthrow}} reader]
   (let [opts (assoc default-opts
                     :read-cond (:read-cond opts)
                     :features (:features opts)
                     :readers (fn [sym]
                                (resolve-tag sym))
                     :auto-resolve (fn [alias]
                                     (@alias-map alias)))
         v (e/parse-next reader opts)]
     (if (identical? ::e/eof v)
       (if (identical? :eofthrow eof)
         (throw (java.io.EOFException.))
         eof)
       v)))
  ([reader eof-error? sentinel]
   (let [v (e/parse-next reader default-opts)]
     (if (identical? ::e/eof v)
       (if eof-error?
         (throw (java.io.EOFException.))
         sentinel)
       v))))

(defn resolve-symbol [sym]
  (p/fully-qualify (ctx/get-ctx) sym))

(def reader-namespace
  {'read (sci/copy-var read rns)
   'resolve-symbol (sci/copy-var resolve-symbol rns)
   '*default-data-reader-fn* default-data-reader-fn
   '*alias-map* alias-map})
