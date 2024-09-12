(ns babashka.impl.http-client
  (:require
   [babashka.http-client]
   [babashka.http-client.interceptors]
   [babashka.http-client.websocket]
   [sci.core :as sci]))

(def hns (sci/create-ns 'babashka.http-client))
(def wns (sci/create-ns 'babashka.http-client.websocket))
(def ins (sci/create-ns 'babashka.http-client.interceptors))

(def http-client-namespace
  (sci/copy-ns babashka.http-client hns))

(def http-client-websocket-namespace
  (sci/copy-ns babashka.http-client.websocket wns))

(def http-client-interceptors-namespace
  (sci/copy-ns babashka.http-client.interceptors ins))
