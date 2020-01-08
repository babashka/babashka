(ns babashka.impl.clojure.java.io
  {:no-doc true}
  (:require [clojure.java.io :as io]))

(def io-namespace
  {'as-relative-path io/as-relative-path
   'as-url io/as-url
   'copy io/copy
   'delete-file io/delete-file
   'file io/file
   'input-stream io/input-stream
   'make-parents io/make-parents
   'output-stream io/output-stream
   'reader io/reader
   'writer io/writer})
