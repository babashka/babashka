(ns babashka.impl.ring-middleware-webjars
  (:require [ring.middleware.webjars :as webjars]
            [sci.core :as sci :refer [copy-var]]))

(def wns (sci/create-ns 'ring.middleware.webjars nil))

(def ring-middleware-webjars-namespace
  {:obj wns
   'wrap-webjars (copy-var webjars/wrap-webjars wns)})
