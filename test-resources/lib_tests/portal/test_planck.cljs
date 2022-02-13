(ns portal.test-planck
  (:require [cljs.test :refer [run-tests]]
            [planck.core :refer [exit]]
            [portal.runtime.cson-test]))

(defmethod cljs.test/report [:cljs.test/default :end-run-tests] [m]
  (when-not (cljs.test/successful? m)
    (exit 1)))

(defn -main []
  (run-tests 'portal.runtime.cson-test))
