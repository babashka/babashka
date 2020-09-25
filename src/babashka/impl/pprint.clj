(ns babashka.impl.pprint
  {:no-doc true}
  (:require [clojure.pprint :as pprint]
            [sci.core :as sci]
            [sci.impl.namespaces :refer [copy-var]]
            [sci.impl.vars :as vars]))

(alter-var-root #'pprint/write-option-table
                (fn [m]
                  (zipmap (keys m)
                          (map find-var (vals m)))))

(def new-table-ize
  (fn [t m]
    (apply hash-map
           (mapcat
            #(when-let [v (get t (key %))] [v (val %)])
            m))))

(alter-var-root #'pprint/table-ize (constantly new-table-ize))

(alter-meta! #'pprint/with-pretty-writer dissoc :private)
(alter-meta! #'pprint/pretty-writer? dissoc :private)
(alter-meta! #'pprint/make-pretty-writer dissoc :private)

(def new-write
  (fn [object & kw-args]
    (let [options (merge {:stream true} (apply hash-map kw-args))]
      (with-bindings (new-table-ize @#'pprint/write-option-table options)
        (with-bindings
          (if (or (not (= pprint/*print-base* 10)) pprint/*print-radix*)
            {#'pr @#'pprint/pr-with-base} {})
          (let [optval (if (contains? options :stream)
                         (:stream options)
                         true)
                base-writer (condp = optval
                              nil (java.io.StringWriter.)
                              true *out*
                              optval)]
            (if pprint/*print-pretty*
              (pprint/with-pretty-writer base-writer
                (pprint/write-out object))
              (binding [*out* base-writer]
                (pr object)))
            (if (nil? optval)
              (.toString ^java.io.StringWriter base-writer))))))))

(alter-var-root #'pprint/write (constantly new-write))

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
