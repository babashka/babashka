(ns babashka.impl.http-kit
  {:no-doc true}
  (:require #_[org.httpkit.server :as srv]
            [org.httpkit.client :as client]
            [org.httpkit.sni-client :as sni-client]
            [sci.core :as sci :refer [copy-var]]))

(alter-var-root #'org.httpkit.client/*default-client* (fn [_] sni-client/default-client))

(def sns (sci/create-ns 'org.httpkit.server nil))
(def cns (sci/create-ns 'org.httpkit.client nil))

(def http-kit-server-namespace
  {#_#_'run-server (copy-var srv/run-server sns)})

(def http-kit-client-namespace
  {'request (copy-var client/request cns)
   'get (copy-var client/get cns)})
