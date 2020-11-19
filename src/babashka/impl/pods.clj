(ns babashka.impl.pods
  {:no-doc true}
  (:refer-clojure :exclude [read])
  (:require [babashka.impl.common :refer [ctx]]
            [babashka.pods.sci :as pods]))

(def pods-namespace
  {'load-pod (fn [& args]
               (apply pods/load-pod @ctx args))
   'invoke pods/invoke
   'unload-pod pods/unload-pod})
