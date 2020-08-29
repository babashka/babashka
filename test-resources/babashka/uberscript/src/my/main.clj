(ns my.main
  (:require [my.impl1 :as impl1] ;; my.impl is already loaded, so it will not be loaded again (normally)
            ;; but my.impl2 also loads my.impl
            [my.impl2]))

;; top-level requires are also supported
(require '[my.impl3 :refer [foo]])

(defn -main [& args]
  ;; this function is defined non-top-level and may cause problems
  (foo)
  ;; this should just return args
  (impl1/impl-fn args))
