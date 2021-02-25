(ns babashka.impl.malli
  {:no-doc true}
  (:require [malli.core :as m]
            [malli.error :as me]
            [malli.transform :as mt]
            [sci.core :as sci :refer [copy-var]]))

(def mns (sci/create-ns 'malli.core nil))
(def tns (sci/create-ns 'malli.transform nil))
(def ens (sci/create-ns 'malli.error nil))

(def malli-namespace
  {'validate (copy-var m/validate mns)
   'validator (copy-var m/validator mns)
   'explain (copy-var m/explain mns)
   'decoder (copy-var m/decoder mns)
   'decode (copy-var m/decode mns)
   })

(def malli-transform-namespace
  {'string-transformer (copy-var mt/string-transformer tns)
   })

(def malli-error-namespace
  {'humanize (copy-var me/humanize ens)
   })
