(ns babashka.impl.clojure.java.process
  (:require [clojure.java.process]
            [sci.core :as sci]))

(def cjp (sci/create-ns 'clojure.java.process nil))
;; io-task is :skip-wiki upstream; tools.build calls it
(def cjp-namespace (sci/copy-ns clojure.java.process cjp {:exclude-when-meta [:no-doc]}))
