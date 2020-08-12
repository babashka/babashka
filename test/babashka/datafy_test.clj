(ns babashka.datafy-test
  (:require [babashka.test-utils :as tu]
            [clojure.edn :as edn]
            [clojure.test :as t :refer [deftest is testing]]))

(defn bb [& args]
  (edn/read-string (apply tu/bb nil (map str args))))

(deftest datafy-test
  (testing "default implementation of datafy works"
    (is (= #{:public} (bb "(require '[clojure.datafy :as d]) (:flags (d/datafy Exception))"))))
  (testing "custom implementation of datafy works"
    (is (= {:number 1} (bb "
(require '[clojure.datafy :as d]
         '[clojure.core.protocols :as p])

(extend-type Number
  p/Datafiable
  (datafy [x]
    {:number x}))

(d/datafy 1)
"))))
  (testing "default implementation of nav works"
    (is (= 1 (bb "(require '[clojure.datafy :as d]) (d/nav {:a 1} :a 1)"))))
  (testing "custom implementation of nav works"
    (is (= \f (bb "
(require '[clojure.datafy :as d]
         '[clojure.core.protocols :as p])

;; this is a bad implementation, just for testing
(extend-type String
  p/Navigable
  (nav [coll k v]
    (.charAt coll k)))

(d/nav \"foo\" 0 nil)
"))))
  (testing "TODO: metadata impl of datafy")
  (testing "TODO: metadata impl of nav"))

;;;; Scratch
(comment
  (t/run-tests *ns*)
  (datafy-test))
