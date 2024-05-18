(ns my.main-main
  (:require [my.impl :as impl])
  (:require [my.impl2]))

(defn -main [& args]
  (prn (impl/impl-fn args)))
