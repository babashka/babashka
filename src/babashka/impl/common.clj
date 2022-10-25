(ns babashka.impl.common
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]))

;; placeholder for ctx
(def ctx (volatile! nil))
(def bb-edn (volatile! nil))
(def debug (volatile! false))
(def version (str/trim (slurp (io/resource "BABASHKA_VERSION"))))
