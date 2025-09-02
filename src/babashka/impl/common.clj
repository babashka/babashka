(ns babashka.impl.common
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [sci.ctx-store :as ctx-store]))

(set! *warn-on-reflection* true)

;; placeholder for ctx
(defn ctx [] (ctx-store/get-ctx))
(def bb-edn (volatile! nil))
(def debug (volatile! false))
(def version (str/trim (slurp (io/resource "BABASHKA_VERSION"))))
(def jvm-loader (.getContextClassLoader (Thread/currentThread)))
(def solo-executor (volatile! clojure.lang.Agent/soloExecutor))
