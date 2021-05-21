(ns babashka.impl.os
  {:no-doc true}
  (:require [babashka.os :as os]
            [sci.core :as sci]))

(def ons (sci/create-ns 'babashka.os nil))

(def os-namespace
  {'set-env (sci/copy-var os/set-env ons)
   'get-env (sci/copy-var os/get-env ons)})




