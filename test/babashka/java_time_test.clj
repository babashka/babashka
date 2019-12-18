(ns babashka.java-time-test
  (:require
   [babashka.test-utils :as test-utils]
   [clojure.edn :as edn]
   [clojure.test :as test :refer [deftest is]]))

(defn bb [expr]
  (edn/read-string (apply test-utils/bb nil [(str expr)])))

(deftest java-time-test
  (is (= "2019-12-18" (bb '(str (java.time.LocalDate/of 2019 12 18)))))
  (is (= "2019-12-01" (bb '(str
                            (-> (java.time.LocalDate/of 2019 12 18)
                                (.minusDays 17)))))))
