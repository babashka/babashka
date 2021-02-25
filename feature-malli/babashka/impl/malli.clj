(ns babashka.impl.malli
  {:no-doc true}
  (:require [malli.core :as m]
            [malli.error :as me]
            [sci.core :as sci :refer [copy-var]]))

(def mns (sci/create-ns 'malli.core nil))
(def ens (sci/create-ns 'malli.core nil))


(def malli-namespace
  {'validate (copy-var m/validate mns)
   'validator (copy-var m/validator mns)
   'explain (copy-var m/explain mns)
   })

(def malli-error-namespace
  {'humanize (copy-var me/humanize ens)
   })
