(ns babashka.pprint-test
  (:require
   [babashka.test-utils :as test-utils]
   [clojure.string :as str]
   [clojure.test :as test :refer [deftest is]]))

(defn bb [& args]
  (str/trim (apply test-utils/bb (map str args))))

(deftest print-length-test
  (is (= "(0 1 2 3 4 5 6 7 8 9 ...)"
         (bb "-e" "(set! *print-length* 10) (clojure.pprint/pprint (range 20))"))))
