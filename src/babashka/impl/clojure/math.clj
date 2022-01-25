(ns babashka.impl.clojure.math
  (:require [clojure.math]
            [sci.core :as sci]))

(def mns (sci/create-ns 'clojure.math nil))
(def math-namespace (sci/copy-ns clojure.math mns))
