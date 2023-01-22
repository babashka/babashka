(ns babashka.impl.http-client
  (:require
   [babashka.http-client]
   [sci.core :as sci]))

(def hns (sci/create-ns 'babashka.http-client))

(def http-client-namespace
  (sci/copy-ns babashka.http-client hns))

