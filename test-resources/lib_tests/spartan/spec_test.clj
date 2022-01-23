(ns spartan.spec-test
  (:require [clojure.test :as t :refer [deftest is]]))

(time (require '[spartan.spec]))
(require '[clojure.spec.alpha :as s])

(deftest spec-test
  (time (s/explain (s/cat :i int? :s string?) [1 :foo]))
  (is (time (s/conform (s/cat :i int? :s string?) [1 "foo"]))))

