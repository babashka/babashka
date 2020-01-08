(ns babashka.impl.cheshire
  {:no-doc true}
  (:require [cheshire.core :as json]))

(def cheshire-core-namespace
  {'encode json/encode
   'generate-string json/generate-string
   'encode-stream json/encode-stream
   'generate-stream json/generate-stream
   'encode-smile json/encode-smile
   'generate-smile json/generate-smile
   'decode json/decode
   'parse-string json/parse-string
   'parse-smile json/parse-smile
   'parse-stream json/parse-stream
   'parsed-seq json/parsed-seq
   'parsed-smile-seq json/parsed-smile-seq
   'decode-smile json/decode-smile})
