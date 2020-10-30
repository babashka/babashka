(ns babashka.impl.http-client
  (:require [babashka.impl.http-client.core :as client]))

(def http-client-namespace
  {'get client/get
   'post client/post
   'send-async client/send-async})
