(ns my.main
  (:require [my.impl :as impl] ;; my.impl is already loaded, so it will not be loaded again (normally)
            [my.impl2] ;; but my.impl2 also loads my.impl
            [my.impl3 :refer [foo]]))

(defn -main [& args]
  ;; this function is defined non-top-level and may cause problems
  (foo)
  ;; this should just return args
  (impl/impl-fn args))
