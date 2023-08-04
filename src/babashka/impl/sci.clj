(ns babashka.impl.sci
  {:no-doc true}
  (:require [sci.core :as sci]))

(def sns (sci/create-ns 'sci.core nil))

(def sci-core-namespace
  (sci/copy-ns sci.core sns))
