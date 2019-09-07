(ns babashka.impl.clojure.java.io
  (:require [clojure.java.io :as io]))

(def io-bindings
  {'io/as-relative-path io/as-relative-path
   'io/copy io/copy
   'io/delete-file io/delete-file
   'io/file io/file
   'io/input-stream io/input-stream
   'io/make-parents io/make-parents
   'io/output-stream io/output-stream
   'io/reader io/reader
   'io/writer io/writer})
