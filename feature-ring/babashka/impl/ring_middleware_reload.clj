(ns babashka.impl.ring-middleware-reload
  (:require [ring.middleware.reload :as reload]
            [sci.core :as sci :refer [copy-var]]))

(def wns (sci/create-ns 'ring.middleware.reload nil))

(def ring-middleware-reload-namespace
  {:obj wns
   ;; 'wrap-reload (copy-var reload/wrap-reload wns) ;; blows up the GraalVM-binary
   })
