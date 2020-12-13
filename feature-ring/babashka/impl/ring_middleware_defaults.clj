(ns babashka.impl.ring-middleware-defaults
  (:require [ring.middleware.defaults :as defaults]
            [ring.middleware.multipart-params]
            [ring.middleware.multipart-params.temp-file]
            [sci.core :as sci :refer [copy-var]]))

(alter-var-root #'ring.middleware.multipart-params/default-store (constantly (delay ring.middleware.multipart-params.temp-file/temp-file-store)))

(def dns (sci/create-ns 'ring.middleware.defaults nil))

(def ring-middleware-defaults-namespace
  {:obj dns
   'wrap-defaults (copy-var defaults/wrap-defaults dns)
   'api-defaults (copy-var defaults/api-defaults dns)
   'site-defaults (copy-var defaults/site-defaults dns)})
