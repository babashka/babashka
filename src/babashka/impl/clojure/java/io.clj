(ns babashka.impl.clojure.java.io
  {:no-doc true}
  (:require [babashka.impl.classpath :as cp]
            [clojure.java.io :as io]
            [sci.core :as sci :refer [copy-var]]))

(def io-ns (sci/create-ns 'clojure.java.io nil))

(def io-namespace
  {'Coercions (copy-var io/Coercions io-ns)
   'as-file (copy-var io/as-file io-ns)
   'as-url (copy-var io/as-url io-ns)
   'IOFactory (copy-var io/IOFactory io-ns)
   'make-reader (copy-var io/make-reader io-ns)
   'make-writer (copy-var io/make-writer io-ns)
   'make-input-stream (copy-var io/make-input-stream io-ns)
   'make-output-stream (copy-var io/make-output-stream io-ns)
   'as-relative-path (copy-var io/as-relative-path io-ns)
   'file (copy-var io/file io-ns)
   'copy (copy-var io/copy io-ns)
   'delete-file (copy-var io/delete-file io-ns)
   'input-stream (copy-var io/input-stream io-ns)
   'make-parents (copy-var io/make-parents io-ns)
   'output-stream (copy-var io/output-stream io-ns)
   'reader (copy-var io/reader io-ns)
   'writer (copy-var io/writer io-ns)
   'resource (copy-var cp/resource io-ns)})
