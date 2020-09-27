(ns babashka.impl.ring-util-http-response
  (:require [ring.util.http-response :as http-response]
            [sci.core :as sci :refer [copy-var]]))

(def hns (sci/create-ns 'ring.util.http-response nil))

(def ring-util-http-response-namespace
  {:obj hns
   'ok (copy-var http-response/ok hns)
   'bad-request (copy-var http-response/bad-request hns)
   'internal-server-error (copy-var http-response/internal-server-error hns)})
