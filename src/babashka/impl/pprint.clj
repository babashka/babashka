(ns babashka.impl.pprint
  {:no-doc true}
  (:require [clojure.pprint :as pprint]
            [sci.core :as sci]))

(defonce patched? (volatile! false))

(when-not @patched?
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

(when-not @patched?
  (alter-var-root #'pprint/table-ize (constantly new-table-ize))
  (alter-meta! #'pprint/write-option-table dissoc :private)
  (alter-meta! #'pprint/with-pretty-writer dissoc :private)
  (alter-meta! #'pprint/pretty-writer? dissoc :private)
  (alter-meta! #'pprint/make-pretty-writer dissoc :private)
  (alter-meta! #'pprint/execute-format dissoc :private))

(def pprint-ns (sci/create-ns 'clojure.pprint nil))

(def print-right-margin
  (sci/new-dynamic-var '*print-right-margin* pprint/*print-right-margin* {:ns pprint-ns}))

(def print-pprint-dispatch
  (sci/new-dynamic-var '*print-pprint-dispatch* pprint/*print-pprint-dispatch* {:ns pprint-ns}))

(def print-miser-width
  (sci/new-dynamic-var '*print-miser-width* pprint/*print-miser-width* {:ns pprint-ns}))

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

(when-not @patched?
  (alter-var-root #'pprint/write (constantly new-write)))

(defn print-table
  "Prints a collection of maps in a textual table. Prints table headings
   ks, and then a line of output for each row, corresponding to the keys
   in ks. If ks are not specified, use the keys of the first item in rows."
  ([rows] (print-table (keys (first rows)) rows))
  ([ks rows]
   (binding [*out* @sci/out]
     (pprint/print-table ks rows))))

(defmacro formatter-out
  "Makes a function which can directly run format-in. The function is
  fn [& args] ... and returns nil. This version of the formatter macro is
  designed to be used with *out* set to an appropriate Writer. In particular,
  this is meant to be used as part of a pretty printer dispatch method.
  format-in can be either a control string or a previously compiled format."
  {:added "1.2"}
  [format-in]
  `(let [format-in# ~format-in
         cf# (if (string? format-in#) (#'clojure.pprint/cached-compile format-in#) format-in#)]
     (fn [& args#]
       (let [navigator# (#'clojure.pprint/init-navigator args#)]
         (#'clojure.pprint/execute-format cf# navigator#)))))

(defn pprint
  "Pretty print object to the optional output writer. If the writer is not provided,
  print the object to the currently bound value of *out*."
  ([s]
   (pprint s @sci/out))
  ([s writer]
   (binding [pprint/*print-right-margin* @print-right-margin
             pprint/*print-pprint-dispatch* @print-pprint-dispatch
             pprint/*print-miser-width* @print-miser-width
             *print-meta* @sci/print-meta
             *print-readably* @sci/print-readably]
     (pprint/pprint s writer))))

(defn cl-format
   "An implementation of a Common Lisp compatible format function. cl-format formats its
arguments to an output stream or string based on the format control string given. It 
supports sophisticated formatting of structured data.
Writer is an instance of java.io.Writer, true to output to *out* or nil to output 
to a string, format-in is the format control string and the remaining arguments 
are the data to be formatted.
The format control string is a string to be output with embedded 'format directives' 
describing how to format the various arguments passed in.
If writer is nil, cl-format returns the formatted result string. Otherwise, cl-format 
returns nil.
For example:
 (let [results [46 38 22]]
        (cl-format true \"There ~[are~;is~:;are~]~:* ~d result~:p: ~{~d~^, ~}~%\" 
                   (count results) results))
Prints to *out*:
 There are 3 results: 46, 38, 22
Detailed documentation on format control strings is available in the \"Common Lisp the 
Language, 2nd edition\", Chapter 22 (available online at:
http://www.cs.cmu.edu/afs/cs.cmu.edu/project/ai-repository/ai/html/cltl/clm/node200.html#SECTION002633000000000000000) 
and in the Common Lisp HyperSpec at 
http://www.lispworks.com/documentation/HyperSpec/Body/22_c.htm
"
  [& args]
  ;; bind *out* to sci/out, so with-out-str works
  (binding [*out* @sci/out]
    (apply pprint/cl-format args)))

(defn execute-format
  "We need to bind sci/out to *out* so all calls to clojure.core/print are directed
  to the writer bound to *out* by the cl-format logic."
  [& args]
  (sci/binding [sci/out *out*]
    (apply #'pprint/execute-format args)))

(defn get-pretty-writer
   "Returns the java.io.Writer passed in wrapped in a pretty writer proxy, unless it's 
already a pretty writer. Generally, it is unnecessary to call this function, since pprint,
write, and cl-format all call it if they need to. However if you want the state to be 
preserved across calls, you will want to wrap them with this. 
For example, when you want to generate column-aware output with multiple calls to cl-format, 
do it like in this example:
    (defn print-table [aseq column-width]
      (binding [*out* (get-pretty-writer *out*)]
        (doseq [row aseq]
          (doseq [col row]
            (cl-format true \"~4D~7,vT\" col column-width))
          (prn))))
Now when you run:
    user> (print-table (map #(vector % (* % %) (* % % %)) (range 1 11)) 8)
It prints a table of squares and cubes for the numbers from 1 to 10:
       1      1       1    
       2      4       8    
       3      9      27    
       4     16      64    
       5     25     125    
       6     36     216    
       7     49     343    
       8     64     512    
       9     81     729    
      10    100    1000"
  [writer]
  (binding [pprint/*print-right-margin* @print-right-margin
            pprint/*print-miser-width* @print-miser-width]
    (pprint/get-pretty-writer writer)))

(def pprint-namespace
  {'pp (sci/copy-var pprint/pp pprint-ns)
   'pprint (sci/copy-var pprint pprint-ns)
   'print-table (sci/copy-var print-table pprint-ns)
   '*print-right-margin* print-right-margin
   'cl-format (sci/copy-var cl-format pprint-ns)
   ;; we alter-var-root-ed write above, so this should copy the right function
   'write (sci/copy-var pprint/write pprint-ns)
   'simple-dispatch (sci/copy-var pprint/simple-dispatch pprint-ns)
   'formatter-out (sci/copy-var formatter-out pprint-ns)
   'cached-compile (sci/copy-var pprint/cached-compile pprint-ns) #_(sci/new-var 'cache-compile @#'pprint/cached-compile (meta @#'pprint/cached-compile))
   'init-navigator (sci/copy-var pprint/init-navigator pprint-ns)
   'execute-format (sci/copy-var execute-format pprint-ns)
   'with-pprint-dispatch (sci/copy-var pprint/with-pprint-dispatch pprint-ns)
   '*print-pprint-dispatch* print-pprint-dispatch
   '*print-miser-width* print-miser-width
   'get-pretty-writer (sci/copy-var get-pretty-writer pprint-ns)})

(vreset! patched? true)
