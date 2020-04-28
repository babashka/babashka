(ns babashka.impl.modules
  {:no-doc true}
  (:import [babashka.modules PgNextJDBC]))

(defn load-library [s]
  (clojure.lang.RT/loadLibrary s))

(def modules-namespace
  {'load-library load-library
   'pg-init #(PgNextJDBC/init)})
