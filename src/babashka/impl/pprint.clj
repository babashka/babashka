(ns babashka.impl.pprint
  {:no-doc true}
  (:require [babashka.impl.clojure.pprint :as pprint]
            [sci.core :as sci]
            [sci.impl.namespaces :refer [copy-var]]
            [sci.impl.vars :as vars]))

(def pprint-ns (vars/->SciNamespace 'clojure.pprint nil))

(def print-right-margin (sci/new-dynamic-var 'print-right-margin 70 {:ns pprint-ns}))

(defn print-table
  "Prints a collection of maps in a textual table. Prints table headings
   ks, and then a line of output for each row, corresponding to the keys
   in ks. If ks are not specified, use the keys of the first item in rows."
  ([rows] (print-table (keys (first rows)) rows))
  ([ks rows]
   (binding [*out* @sci/out]
     (pprint/print-table ks rows))))

(defn pprint
  "Pretty print object to the optional output writer. If the writer is not provided,
  print the object to the currently bound value of *out*."
  ([s]
   (pprint s @sci/out))
  ([s writer]
   (binding [pprint/*print-right-margin* @print-right-margin]
     (pprint/pprint s writer))))

(def pprint-namespace
  {'pprint (copy-var pprint pprint-ns)
   'print-table (copy-var pprint/print-table pprint-ns)
   '*print-right-margin* print-right-margin
   'cl-format (copy-var pprint/cl-format pprint-ns)
   })
