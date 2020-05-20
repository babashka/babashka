(ns babashka.impl.curl
  {:no-doc true}
  (:require [babashka.curl :as curl]
            [sci.impl.namespaces :refer [copy-var]]
            [sci.impl.vars :as vars]))

(def tns (vars/->SciNamespace 'babashka.curl nil))

(def curl-namespace
  {'request (copy-var curl/request tns)
   'get (copy-var curl/get tns)
   'patch (copy-var curl/patch tns)
   'post (copy-var curl/post tns)
   'put (copy-var curl/put tns)
   'head (copy-var curl/head tns)
   'delete (copy-var curl/delete tns)})
