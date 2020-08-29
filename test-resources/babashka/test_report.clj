(ns foo
  (:require
   [clojure.test :as t]))

(t/deftest bar
  (t/is (= 1 2) "1 is not equal to 2"))

(binding [t/report (fn [m]
                     (prn (update m :var (comp :name meta))))]
  (t/test-var #'bar))
