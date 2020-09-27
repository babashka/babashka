(ns babashka.impl.muuntaja-core
  (:require [muuntaja.core :as m]
            [sci.core :as sci :refer [copy-var]]))

(def mns (sci/create-ns 'muuntaja.core nil))

(def muuntaja-core-namespace
  {:obj mns
   'create (copy-var m/create mns)
   'default-options (copy-var m/default-options mns)})
