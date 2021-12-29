(ns cognitect.test-runner.sample-property-test
  (:require [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]))

(defspec first-element-is-min-after-sorting 100
  (prop/for-all [v (gen/not-empty (gen/vector gen/int))]
                (= (apply min v)
                   (first (sort v)))))
