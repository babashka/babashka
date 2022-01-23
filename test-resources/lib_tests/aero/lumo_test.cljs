(ns aero.lumo-test
  (:require
   aero.core-test
   [cljs.test :refer-macros [deftest is testing run-tests]]))

(def resolve-p (atom nil))

(def p (new js/Promise (fn [resolve reject]
                         (reset! resolve-p resolve))))

(defmethod cljs.test/report [:cljs.test/default :end-run-tests]
  [m]
  (@resolve-p m))

(defn -main [& argv]
  (println "Testing with lumo")
  (run-tests 'aero.core-test)
  (-> p
      (.then (fn [m]
               (.exit (js/require "process")
                      (if (cljs.test/successful? m)
                        0
                        1))))))
