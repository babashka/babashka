(ns babashka.impl.cheshire
  {:no-doc true}
  (:require [cheshire.core :as json]
            [cheshire.factory :as fact]
            [cheshire.generate :as gen]
            [sci.core :as sci :refer [copy-var]]))

(def tns (sci/create-ns 'cheshire.core nil))
(def fns (sci/create-ns 'cheshire.factory nil))
(def gns (sci/create-ns 'cheshire.generate nil))

(def json-factory (sci/new-dynamic-var '*json-factory* nil {:ns fns}))

;; wrap cheshire fns to support `*json-factory*` dynamic var

(defn generate-string
  ([obj]
   (binding [fact/*json-factory* @json-factory]
     (json/generate-string obj)))
  ([obj opt-map]
   (binding [fact/*json-factory* @json-factory]
     (json/generate-string obj opt-map))))

(defn generate-stream
  ([obj writer]
   (binding [fact/*json-factory* @json-factory]
     (json/generate-stream obj writer)))
  ([obj writer opt-map]
   (binding [fact/*json-factory* @json-factory]
     (json/generate-stream obj writer opt-map))))

(defn parse-string
  ([string]
   (when string
     (binding [fact/*json-factory* @json-factory]
       (json/parse-string string))))
  ([string key-fn]
   (when string
     (binding [fact/*json-factory* @json-factory]
       (json/parse-string string key-fn))))
  ([^String string key-fn array-coerce-fn]
   (when string
     (binding [fact/*json-factory* @json-factory]
       (json/parse-string string key-fn array-coerce-fn)))))

(defn parse-string-strict
  ([string]
   (when string
     (binding [fact/*json-factory* @json-factory]
       (json/parse-string-strict string))))
  ([string key-fn]
   (when string
     (binding [fact/*json-factory* @json-factory]
       (json/parse-string-strict string key-fn))))
  ([^String string key-fn array-coerce-fn]
   (when string
     (binding [fact/*json-factory* @json-factory]
       (json/parse-string-strict string key-fn array-coerce-fn)))))

(defn parse-stream
  ([rdr]
   (when rdr
     (binding [fact/*json-factory* @json-factory]
       (json/parse-stream rdr))))
  ([rdr key-fn]
   (when rdr
     (binding [fact/*json-factory* @json-factory]
       (json/parse-stream rdr key-fn))))
  ([rdr key-fn array-coerce-fn]
   (when rdr
     (binding [fact/*json-factory* @json-factory]
       (json/parse-stream rdr key-fn array-coerce-fn)))))

(defn parse-stream-strict
  ([rdr]
   (when rdr
     (binding [fact/*json-factory* @json-factory]
       (json/parse-stream-strict rdr))))
  ([rdr key-fn]
   (when rdr
     (binding [fact/*json-factory* @json-factory]
       (json/parse-stream-strict rdr key-fn))))
  ([rdr key-fn array-coerce-fn]
   (when rdr
     (binding [fact/*json-factory* @json-factory]
       (json/parse-stream-strict rdr key-fn array-coerce-fn)))))

(defn parsed-seq
  ([reader]
   (binding [fact/*json-factory* @json-factory]
     (json/parsed-seq reader)))
  ([reader key-fn]
   (binding [fact/*json-factory* @json-factory]
     (json/parsed-seq reader key-fn)))
  ([reader key-fn array-coerce-fn]
   (binding [fact/*json-factory* @json-factory]
     (json/parsed-seq reader key-fn array-coerce-fn))))

(def cheshire-core-namespace
  {'encode (copy-var generate-string tns)
   'generate-string (copy-var generate-string tns)
   'encode-stream (copy-var generate-stream tns)
   'generate-stream (copy-var generate-stream tns)
   ;;'encode-smile (copy-var json/encode-smile tns)
   ;;'generate-smile (copy-var json/generate-smile tns)
   'decode (copy-var parse-string tns)
   'parse-string (copy-var parse-string tns)
   'parse-string-strict (copy-var parse-string-strict tns)
   ;;'parse-smile (copy-var json/parse-smile tns)
   'parse-stream (copy-var parse-stream tns)
   'parse-stream-strict (copy-var parse-stream-strict tns)
   'parsed-seq (copy-var parsed-seq tns)
   ;;'parsed-smile-seq (copy-var json/parsed-smile-seq tns)
   ;;'decode-smile (copy-var json/decode-smile tns)
   'default-pretty-print-options (copy-var json/default-pretty-print-options tns)
   'create-pretty-printer (copy-var json/create-pretty-printer tns)})

(def cheshire-factory-namespace
  {'*json-factory* json-factory
   'default-factory-options (copy-var fact/default-factory-options fns)
   'json-factory (copy-var fact/json-factory fns)
   'make-json-factory (copy-var fact/make-json-factory fns)})

(def cheshire-generate-namespace
  {'add-encoder (copy-var gen/add-encoder fns)})
