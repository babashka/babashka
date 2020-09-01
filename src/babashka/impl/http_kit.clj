(ns babashka.impl.http-kit
  {:no-doc true}
  (:require [org.httpkit.server :as srv]
            [sci.core :as sci :refer [copy-var]]))

(def tns (sci/create-ns 'org.httpkit.server nil))

(def http-kit-server-namespace
  {'run-server (copy-var srv/run-server tns)})

