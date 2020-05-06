(ns babashka.impl.cheshire
  {:no-doc true}
  (:require [cheshire.core :as json]
            [sci.impl.namespaces :refer [copy-var]]
            [sci.impl.vars :as vars]))

(def tns (vars/->SciNamespace 'cheshire.core nil))

(def cheshire-core-namespace
  {'encode (copy-var json/encode tns)
   'generate-string (copy-var json/generate-string tns)
   'encode-stream (copy-var json/encode-stream tns)
   'generate-stream (copy-var json/generate-stream tns)
   'encode-smile (copy-var json/encode-smile tns)
   'generate-smile (copy-var json/generate-smile tns)
   'decode (copy-var json/decode tns)
   'parse-string (copy-var json/parse-string tns)
   'parse-string-strict (copy-var json/parse-string-strict tns)
   'parse-smile (copy-var json/parse-smile tns)
   'parse-stream (copy-var json/parse-stream tns)
   'parse-stream-strict (copy-var json/parse-stream-strict tns)
   'parsed-seq (copy-var json/parsed-seq tns)
   'parsed-smile-seq (copy-var json/parsed-smile-seq tns)
   'decode-smile (copy-var json/decode-smile tns)})
