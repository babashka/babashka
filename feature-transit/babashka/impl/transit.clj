(ns babashka.impl.transit
  (:require [cognitect.transit :as transit]
            [sci.impl.namespaces :refer [copy-var]]
            [sci.impl.vars :as vars]))


(def tns (vars/->SciNamespace 'cognitect.transit nil))

(def transit-namespace
  {'write (copy-var transit/write tns)
   'writer (copy-var transit/writer tns)
   'write-handler (copy-var transit/write-handler tns)
   'write-meta (copy-var transit/write-meta tns)
   'read (copy-var transit/read tns)
   'reader (copy-var transit/reader tns)
   'read-handler (copy-var transit/read-handler tns)})
