(ns babashka.impl.ring-middleware-content-type
  (:require [ring.middleware.content-type :as content-type]
            [sci.core :as sci :refer [copy-var]]))

(def cns (sci/create-ns 'ring.middleware.content-type nil))

(def ring-middleware-content-type-namespace
  {:obj cns
   'wrap-content-type (copy-var content-type/wrap-content-type cns)})
