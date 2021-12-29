(ns missing.test.old-methods
  (:require [clojure.test :as t]
            [missing.test.assertions :refer [register!]]))

(defmethod t/report #?(:clj :begin-test-var
                       :cljs [::t/default :begin-test-var]) [_]
  (println "Begin test var."))

(defmethod t/report #?(:clj :end-test-var
                       :cljs [::t/default :end-test-var]) [_]
  (println "End test var."))

(register! {:throw? false})
