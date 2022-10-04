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

(deftest print-namespaced-map-test
  (test/testing
      "Testing disabling of printing namespace maps..."
      (is (= "{:a/x 1, :a/y 2, :a/z {:b/x 10, :b/y 20}}"
            (bb "-e" "(binding [*print-namespace-maps* false] (clojure.pprint/pprint {:a/x 1 :a/y 2 :a/z {:b/x 10 :b/y 20}}))"))))
  (test/testing
      "Testing manually enabling printing namespace maps..."
      (is (= "#:a{:x 1, :y 2, :z #:b{:x 10, :y 20}}"
             (bb "-e" "(binding [*print-namespace-maps* true] (clojure.pprint/pprint {:a/x 1 :a/y 2 :a/z {:b/x 10 :b/y 20}}))")))))
