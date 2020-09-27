(ns babashka.impl.ring-middleware-anti-forgery
  (:require [ring.middleware.anti-forgery :as anti-forgery]
            [sci.core :as sci :refer [copy-var]]))

(def ans (sci/create-ns 'ring.middleware.anti-forgery nil))

(def ring-middleware-anti-forgery-namespace
  {:obj ans
   'wrap-anti-forgery (copy-var anti-forgery/wrap-anti-forgery ans)})
