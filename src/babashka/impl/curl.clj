(ns babashka.impl.curl
  {:no-doc true}
  (:require
   [babashka.curl :as curl]
   [sci.core :as sci :refer [copy-var]]))

(def tns (sci/create-ns 'babashka.curl nil))

(def curl-namespace
  {'request (copy-var curl/request tns)
   'get (copy-var curl/get tns)
   'patch (copy-var curl/patch tns)
   'post (copy-var curl/post tns)
   'put (copy-var curl/put tns)
   'head (copy-var curl/head tns)
   'delete (copy-var curl/delete tns)})
