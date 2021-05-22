(ns babashka.impl.pods
  {:no-doc true}
  (:refer-clojure :exclude [read])
  (:require [babashka.impl.common :refer [ctx]]
            [babashka.pods.sci :as pods]
            [sci.core :as sci]))

(defn load-pod [& args]
  (apply pods/load-pod @ctx args))

(def podns (sci/create-ns 'babashka.pods nil))

(def pods-namespace
  {'load-pod (sci/copy-var load-pod podns)
   'invoke (sci/copy-var pods/invoke podns)
   'unload-pod (sci/copy-var pods/unload-pod podns)
   'add-transit-read-handler! (sci/copy-var pods/add-transit-read-handler! podns)
   'add-transit-write-handler! (sci/copy-var pods/add-transit-write-handler! podns)
   'set-default-transit-write-handler! (sci/copy-var pods/set-default-transit-write-handler! podns)})
