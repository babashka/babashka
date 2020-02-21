(require '[clojure.test :refer [is deftest] :as t])

(defmethod t/assert-expr 'roughly [msg form]
  `(let [op1# ~(nth form 1)
         op2# ~(nth form 2)
         tolerance# (if (= 4 ~(count form)) ~(last form) 2)
         decimals# (/ 1. (Math/pow 10 tolerance#))
         result# (< (Math/abs (- op1# op2#)) decimals#)]
     (t/do-report 
      {:type (if result# :pass :fail)
       :message ~msg
       :expected (format "%s should be roughly %s with %s tolerance" 
                         op1# op2# decimals#)
       :actual result#})
     result#))

(deftest PI-test
  (is (roughly 3.14 Math/PI 2))
  (is (roughly 3.14 Math/PI 3)))

(t/test-var #'PI-test)
