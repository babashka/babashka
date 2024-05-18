(ns babashka.impl.clojure.reflect
  (:require [clojure.reflect]
            [sci.core :as sci]))

(def rns (sci/create-ns 'clojure.reflect))

(def reflect-namespace {'reflect (sci/copy-var clojure.reflect/reflect rns)})
