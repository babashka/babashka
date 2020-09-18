(ns babashka.impl.httpkit-client
  {:no-doc true}
  (:require [org.httpkit.client :as client]
            [org.httpkit.sni-client :as sni-client]
            [sci.core :as sci :refer [copy-var]]))

(alter-var-root #'org.httpkit.client/*default-client* (fn [_] sni-client/default-client))

(def sns (sci/create-ns 'org.httpkit.server nil))
(def cns (sci/create-ns 'org.httpkit.client nil))

(def default-client (sci/new-dynamic-var '*default-client* client/default-client {:ns cns}))

(defn request
  ([req]
   (binding [client/*default-client* @default-client]
     (client/request req)))
  ([req cb]
   (binding [client/*default-client* @default-client]
     (client/request req cb))))

(def httpkit-client-namespace
  {'request   (sci/new-var 'request request {:doc (:doc (meta #'client/request))
                                                    :ns cns})
   'get       (copy-var client/get cns)
   'options   (copy-var client/options cns)
   'put       (copy-var client/put cns)
   'lock      (copy-var client/lock cns)
   'report    (copy-var client/report cns)
   'proppatch (copy-var client/proppatch cns)
   'copy      (copy-var client/copy cns)
   'patch     (copy-var client/patch cns)
   'make-ssl-engine (copy-var client/make-ssl-engine cns)
   'move      (copy-var client/move cns)
   'delete    (copy-var client/delete cns)
   'make-client (copy-var client/make-client cns)
   'head      (copy-var client/head cns)
   'propfind  (copy-var client/propfind cns)
   'max-body-filter (copy-var client/max-body-filter cns)
   'post      (copy-var client/post cns)
   'acl       (copy-var client/acl cns)
   'unlock    (copy-var client/unlock cns)
   'default-client (copy-var client/default-client cns)
   '*default-client* default-client})
