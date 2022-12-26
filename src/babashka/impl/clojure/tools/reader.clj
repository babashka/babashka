(ns babashka.impl.clojure.tools.reader
  (:refer-clojure :exclude [read])
  (:require
   [edamame.core :as e]
   [sci.core :as sci]
   [clojure.tools.reader.reader-types :as rt]))

(def rns (sci/create-ns 'clojure.tools.reader))

(def default-opts
  (e/normalize-opts
   {:all true
    :row-key :line
    :col-key :column
    :location? seq?
    :end-location false}))

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
                     :features (:features opts))
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

(def reader-namespace {'read (sci/copy-var read rns)})
