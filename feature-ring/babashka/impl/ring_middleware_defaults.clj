(ns babashka.impl.ring-middleware-defaults
  (:require [ring.middleware.defaults :as defaults]
            [sci.core :as sci :refer [copy-var]]))

(def dns (sci/create-ns 'ring.middleware.defaults nil))

(def ring-middleware-defaults-namespace
  {:obj dns
   'wrap-defaults (copy-var defaults/wrap-defaults dns)
   'api-defaults (copy-var defaults/api-defaults dns)})
