(ns babashka.impl.ring-middleware-anti-forgery
  (:require [ring.middleware.anti-forgery :as anti-forgery]
            [sci.core :as sci :refer [copy-var]]))

(def ans (sci/create-ns 'ring.middleware.anti-forgery nil))

(defn get-anti-forgery-token []
  anti-forgery/*anti-forgery-token*)

(def ring-middleware-anti-forgery-namespace
  {:obj ans
   'wrap-anti-forgery (copy-var anti-forgery/wrap-anti-forgery ans)
   'get-anti-forgery-token (copy-var get-anti-forgery-token ans)})
