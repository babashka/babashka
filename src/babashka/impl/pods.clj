(ns babashka.impl.pods
  {:no-doc true}
  (:refer-clojure :exclude [read])
  (:require [babashka.pods.sci :as pods]))

(def pods-namespace
  {'load-pod (with-meta pods/load-pod
               {:sci.impl/op :needs-ctx})
   'invoke pods/invoke
   'unload-pod pods/unload-pod})
