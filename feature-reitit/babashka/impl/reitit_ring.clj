(ns babashka.impl.reitit-ring
  (:require [reitit.ring :as ring]
            [sci.core :as sci :refer [copy-var]]))

(def rns (sci/create-ns 'reitit.ring nil))

(def reitit-ring-namespace
  {:obj rns
   'ring-handler (copy-var ring/ring-handler rns)
   'router (copy-var ring/router rns)
   'routes (copy-var ring/routes rns)
   'create-resource-handler (copy-var ring/create-resource-handler rns)
   'create-default-handler (copy-var ring/create-default-handler rns)})
