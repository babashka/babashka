(ns babashka.impl.ring-util-response
  (:require [ring.util.response :as response]
            [sci.core :as sci :refer [copy-var]]))

(def rns (sci/create-ns 'ring.util.response nil))

(def ring-util-response-namespace
  {:obj rns
   'response (copy-var response/response rns)
   'resource-data (copy-var response/resource-data rns)
   'header (copy-var response/header rns)})
