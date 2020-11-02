(ns babashka.impl.jetty
  (:require [ring.adapter.jetty :as http]
            [sci.core :as sci]))

(def jns (sci/create-ns 'ring.adapter.jetty nil))

(def jetty-namespace
  {'run-jetty (sci/copy-var http/run-jetty jns)})
