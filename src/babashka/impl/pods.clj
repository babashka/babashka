(ns babashka.impl.pods
  {:no-doc true}
  (:refer-clojure :exclude [read])
  (:require
   [babashka.impl.common :refer [bb-edn ctx]]
   [babashka.pods.sci :as pods]
   [clojure.java.io :as io]
   [sci.core :as sci]))

(defn load-pod [& args]
  (apply pods/load-pod (ctx) args))

(defn load-pods-metadata [pods-map opts]
  (reduce-kv
    (fn [pod-namespaces pod-spec coord]
      (merge pod-namespaces
             (condp #(contains? %2 %1) coord
               :version
               (pods/load-pod-metadata pod-spec
                                       (merge opts {:cache true}
                                              (select-keys coord [:version :cache])))

               :path
               (pods/load-pod-metadata (-> @bb-edn :file io/file)
                                       pod-spec
                                       (merge opts {:cache true}
                                              (select-keys coord [:path :cache])))

               (throw (IllegalArgumentException.
                        (str (-> coord keys first)
                             " is not a supported pod coordinate type. "
                             "Use :version for registry-hosted pods or :path "
                             "for pods on your local filesystem."))))))
    {} pods-map))

(def podns (sci/create-ns 'babashka.pods nil))

(def pods-namespace
  {'load-pod (sci/copy-var load-pod podns)
   'invoke (sci/copy-var pods/invoke podns)
   'unload-pod (sci/copy-var pods/unload-pod podns)
   'add-transit-read-handler! (sci/copy-var pods/add-transit-read-handler! podns)
   'add-transit-write-handler! (sci/copy-var pods/add-transit-write-handler! podns)
   'set-default-transit-write-handler! (sci/copy-var pods/set-default-transit-write-handler! podns)})
