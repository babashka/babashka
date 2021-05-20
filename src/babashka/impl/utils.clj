(ns babashka.impl.utils
  {:no-doc true}
  (:require [babashka.utils :as utils]
            [sci.core :as sci]))

(def uns (sci/create-ns 'babashka.utils nil))

(def utils-namespace
  {'set-env (sci/copy-var utils/set-env uns)})




