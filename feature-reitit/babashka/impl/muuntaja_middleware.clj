(ns babashka.impl.muuntaja-middleware
  (:require [muuntaja.middleware :as m]
            [sci.core :as sci :refer [copy-var]]))

(def mns (sci/create-ns 'muuntaja.middleware nil))

(def muuntaja-middleware-namespace
  {:obj mns
   'wrap-format (copy-var m/wrap-format mns)
   'wrap-params (copy-var m/wrap-params mns)})
