(ns babashka.impl.curl
  {:no-doc true}
  (:require [babashka.curl :as curl]))

(def curl-namespace
  {'request curl/request
   'get curl/get
   'patch curl/patch
   'post curl/post
   'put curl/put
   'head curl/head
   'curl-command curl/curl-command})
