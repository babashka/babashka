(ns my.main-main
  (:require [my.impl :as impl])
  (:require [my.impl2 :as impl2]))

(defn -main [& args]
  (impl/impl-fn args))
