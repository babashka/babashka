(ns babashka.impl.bencode
  {:no-doc true}
  (:require
   [bencode.core :as bencode]
   [sci.core :as sci :refer [copy-var]]))

(def tns (sci/create-ns 'bencode.core nil))

(def bencode-namespace
  {'read-bencode (copy-var bencode/read-bencode tns)
   'write-bencode (copy-var bencode/write-bencode tns)})
