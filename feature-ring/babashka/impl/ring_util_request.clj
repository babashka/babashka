(ns babashka.impl.ring-util-request
  (:require [ring.util.request :as request]
            [sci.core :as sci :refer [copy-var]]))

(def rns (sci/create-ns 'ring.util.request nil))

(def ring-util-request-namespace
  {:obj rns
   'body-string (copy-var request/body-string rns)})
