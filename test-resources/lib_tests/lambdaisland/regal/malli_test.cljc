(ns lambdaisland.regal.malli-test
  (:require [clojure.test :refer [deftest  is ]]
            [malli.core :as m]
            [malli.error :as me]
            [lambdaisland.regal.malli :as regal-malli]))

(def malli-opts {:registry {:regal regal-malli/regal-schema}})

(def form [:+ "y"])

(def schema (m/schema [:regal form] malli-opts))

(deftest regal-malli-test
  (is (= [:regal [:+ "y"]] (m/form schema)))
  (is (= :regal (m/type schema)))
  (is (= true (m/validate schema "yyy")))
  (is (= ["Pattern does not match"] (me/humanize (m/explain schema "xxx")))))


