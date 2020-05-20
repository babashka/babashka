(ns babashka.impl.bencode
  {:no-doc true}
  (:require [bencode.core :as bencode]
            [sci.impl.namespaces :refer [copy-var]]
            [sci.impl.vars :as vars]))

(def tns (vars/->SciNamespace 'bencode.core nil))

(def bencode-namespace
  {'read-bencode (copy-var bencode/read-bencode tns)
   'write-bencode (copy-var bencode/write-bencode tns)})
