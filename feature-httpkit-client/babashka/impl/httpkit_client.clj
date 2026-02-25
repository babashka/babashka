(ns babashka.impl.httpkit-client
  {:no-doc true
   :clj-kondo/config '{:lint-as {babashka.impl.httpkit-client/defreq clojure.core/declare}}}
  (:refer-clojure :exclude [get])
  (:require [clojure.string :as str]
            [org.httpkit.client :as client]
            [org.httpkit.sni-client :as sni-client]
            [sci.core :as sci :refer [copy-var]]))

(def sni-client (delay (client/make-client {:ssl-configurer sni-client/ssl-configurer})))

(def sns (sci/create-ns 'org.httpkit.sni-client nil))
(def cns (sci/create-ns 'org.httpkit.client nil))

(def default-client (sci/new-dynamic-var '*default-client* sni-client {:ns cns}))
(alter-var-root #'client/*default-client* (constantly sni-client))

(defn request
  ([req]
   (binding [client/*default-client* @default-client]
     (client/request req)))
  ([req cb]
   (binding [client/*default-client* @default-client]
     (client/request req cb))))

(defmacro ^:private defreq [method]
  `(defn ~method
     ~(str "Issues an async HTTP " (str/upper-case method) " request. "
           "See `request` for details.")
     ~'{:arglists '([url & [opts callback]] [url & [callback]])}
     ~'[url & [s1 s2]]
     (if (or (instance? clojure.lang.MultiFn ~'s1) (fn? ~'s1) (keyword? ~'s1))
       (request {:url ~'url :method ~(keyword method)} ~'s1)
       (request (merge ~'s1 {:url ~'url :method ~(keyword method)}) ~'s2))))

(defreq get)
(defreq delete)
(defreq head)
(defreq post)
(defreq put)
(defreq options)
(defreq patch)
(defreq propfind)
(defreq proppatch)
(defreq lock)
(defreq unlock)
(defreq report)
(defreq acl)
(defreq copy)
(defreq move)

(def httpkit-client-namespace
  {'request   (copy-var request cns {:copy-meta-from org.httpkit.client/request})
   'get       (copy-var get cns)
   'options   (copy-var options cns)
   'put       (copy-var put cns)
   'lock      (copy-var lock cns)
   'report    (copy-var report cns)
   'proppatch (copy-var proppatch cns)
   'copy      (copy-var copy cns)
   'patch     (copy-var patch cns)
   'make-ssl-engine (copy-var client/make-ssl-engine cns)
   'move      (copy-var move cns)
   'delete    (copy-var delete cns)
   'make-client (copy-var client/make-client cns)
   'head      (copy-var head cns)
   'propfind  (copy-var propfind cns)
   'max-body-filter (copy-var client/max-body-filter cns)
   'post      (copy-var post cns)
   'acl       (copy-var acl cns)
   'unlock    (copy-var unlock cns)
   'default-client (copy-var client/default-client cns)
   '*default-client* default-client
   'query-string (copy-var client/query-string cns)
   'url-encode (copy-var client/url-encode cns)})

(def sni-client-namespace
  {'ssl-configurer (copy-var sni-client/ssl-configurer sns)
   'default-client (sci/new-var 'default-client sni-client {:ns sns})})
