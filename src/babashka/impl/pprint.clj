(ns babashka.impl.pprint
  {:no-doc true}
  (:require [clojure.pprint :as pprint]
            [sci.core :as sci]))

(defonce patch-option-table
  (alter-var-root #'pprint/write-option-table
                  (fn [m]
                    (zipmap (keys m)
                            (map find-var (vals m))))))

(def new-table-ize
  (fn [t m]
    (apply hash-map
           (mapcat
            #(when-let [v (get t (key %))] [v (val %)])
            m))))

(alter-var-root #'pprint/table-ize (constantly new-table-ize))

(alter-meta! #'pprint/write-option-table dissoc :private)
(alter-meta! #'pprint/with-pretty-writer dissoc :private)
(alter-meta! #'pprint/pretty-writer? dissoc :private)
(alter-meta! #'pprint/make-pretty-writer dissoc :private)

(def new-write
  (fn [object & kw-args]
    (let [options (merge {:stream true} (apply hash-map kw-args))]
      (with-bindings (new-table-ize pprint/write-option-table options)
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

(def pprint-ns (sci/create-ns 'clojure.pprint nil))


(defn print-table
  "Prints a collection of maps in a textual table. Prints table headings
   ks, and then a line of output for each row, corresponding to the keys
   in ks. If ks are not specified, use the keys of the first item in rows."
  ([rows] (print-table (keys (first rows)) rows))
  ([ks rows]
   (binding [*out* @sci/out]
     (pprint/print-table ks rows))))

(def print-right-margin
  (sci/new-dynamic-var '*print-right-margin* pprint/*print-right-margin* {:ns pprint-ns}))

(def print-pprint-dispatch
  (sci/new-dynamic-var '*print-pprint-dispatch pprint/*print-pprint-dispatch* {:ns pprint-ns}))

(defn pprint
  "Pretty print object to the optional output writer. If the writer is not provided,
  print the object to the currently bound value of *out*."
  ([s]
   (pprint s @sci/out))
  ([s writer]
   (binding [pprint/*print-right-margin* @print-right-margin
             pprint/*print-pprint-dispatch* @print-pprint-dispatch]
     (pprint/pprint s writer))))

(def pprint-namespace
  {'pp (sci/copy-var pprint/pp pprint-ns)
   'pprint (sci/copy-var pprint pprint-ns)
   'print-table (sci/copy-var print-table pprint-ns)
   '*print-right-margin* print-right-margin
   'cl-format (sci/copy-var pprint/cl-format pprint-ns)
   ;; we alter-var-root-ed write above, so this should copy the right function
   'write (sci/copy-var pprint/write pprint-ns)
   'simple-dispatch (sci/copy-var pprint/simple-dispatch pprint-ns)
   'formatter-out (sci/copy-var pprint/formatter-out pprint-ns)
   'cached-compile (sci/copy-var pprint/cached-compile pprint-ns) #_(sci/new-var 'cache-compile @#'pprint/cached-compile (meta @#'pprint/cached-compile))
   'init-navigator (sci/copy-var pprint/init-navigator pprint-ns)
   'execute-format (sci/copy-var pprint/execute-format pprint-ns)
   'with-pprint-dispatch (sci/copy-var pprint/with-pprint-dispatch pprint-ns)
   '*print-pprint-dispatch* print-pprint-dispatch})
