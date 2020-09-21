(ns babashka.lambdaisland.regal-test
  (:require [clojure.test :as t :refer [deftest is]]))

(prn :requiring :lambdaisland)
(require '[lambdaisland.regal :as regal])
(prn ::done :requiring :lambdaisland)

(def r [:cat
        [:+ [:class [\a \z]]]
        "="
        [:+ [:not \=]]])

(deftest regal-test
  (is (= "[a-z]+=[^=]+" (str (regal/regex r))))
  (is (= "foo=bar" (re-matches (regal/regex r) "foo=bar"))))
