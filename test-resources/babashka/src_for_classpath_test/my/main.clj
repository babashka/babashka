(ns my.main
  (:require [my.impl :as impl]))

(defn -main [& args]
  (impl/impl-fn args))
