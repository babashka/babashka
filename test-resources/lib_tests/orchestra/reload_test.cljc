(ns orchestra.reload-test
  (:require [clojure.test :refer :all]
            [clojure.spec.alpha :as s]
            [orchestra.spec.test :refer :all]))

(deftest in-place-reload
  (testing "Positive"
    (dotimes [_ 5]
      (require 'orchestra.spec.test :reload-all))))
