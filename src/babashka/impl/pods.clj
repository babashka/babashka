(ns babashka.impl.pods
  {:no-doc true}
  (:refer-clojure :exclude [read])
  (:require [babashka.impl.common :refer [ctx]]
            [babashka.pods.sci :as pods]
            [sci.core :as sci]))

(defn load-pod [& args]
  (apply pods/load-pod @ctx args))

(defn load-pods-metadata [pods-map]
  (dorun
    (for [[pod-spec coord] pods-map]
      (condp #(contains? %2 %1) coord
        :version
        (pods/load-pod-metadata @ctx pod-spec (select-keys coord [:version]))

        :local/root
        (pods/load-pod-metadata @ctx pod-spec {:path (:local/root coord)})

        (throw (IllegalArgumentException.
                 (str (-> coord keys first)
                      " is not a supported pod coordinate type. "
                      "Use :version for registry-hosted pods or :local/root "
                      "for pods on your local filesystem.")))))))

(def podns (sci/create-ns 'babashka.pods nil))

(def pods-namespace
  {'load-pod (sci/copy-var load-pod podns)
   'invoke (sci/copy-var pods/invoke podns)
   'unload-pod (sci/copy-var pods/unload-pod podns)
   'add-transit-read-handler! (sci/copy-var pods/add-transit-read-handler! podns)
   'add-transit-write-handler! (sci/copy-var pods/add-transit-write-handler! podns)
   'set-default-transit-write-handler! (sci/copy-var pods/set-default-transit-write-handler! podns)})
