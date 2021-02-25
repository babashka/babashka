(ns babashka.impl.malli
  {:no-doc true}
  (:require [malli.core :as m]
            [sci.core :as sci :refer [copy-var]]))

(def mns (sci/create-ns 'malli.core nil))

(def malli-namespace
  {'validate (copy-var m/validate mns)
   'validator (copy-var m/validator mns)
   })
