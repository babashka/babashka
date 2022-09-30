(ns babashka.impl.common
  (:require [sci.core :as sci]))

;; placeholder for ctx
(def ctx (volatile! (sci/init {})))
(def bb-edn (volatile! nil))
(def debug (volatile! false))
