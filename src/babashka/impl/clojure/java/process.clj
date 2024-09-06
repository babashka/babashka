(ns babashka.impl.clojure.java.process
  (:require [clojure.java.process]
            [sci.core :as sci]))

(def cjp (sci/create-ns 'clojure.java.process nil))
(def cjp-namespace (sci/copy-ns clojure.java.process cjp))
