(ns lambdaisland.regal.spec-gen-test
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as spec-gen]
            [clojure.test :refer [deftest is are testing run-tests]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [lambdaisland.regal :as regal]
            [lambdaisland.regal.parse :as parse]
            [lambdaisland.regal.spec-alpha]))

(def form-gen (s/gen ::regal/form))
(def canonical-form-gen (gen/fmap regal/normalize (s/gen ::regal/form)))

(defspec generated-forms-can-be-converted 100
  (prop/for-all [regal form-gen]
                (try
                  (regal/regex regal)
                  (catch Exception _
                    false))))

(defn- round-trip? [form]
  (try 
    (= form (parse/parse (regal/regex form)))
    (catch Exception _ false)))

(defspec round-trip-property 100
  (prop/for-all* [canonical-form-gen] round-trip?))

(deftest round-trip-test
  (is (round-trip? [:cat "   " [:class "&& "]]))
  (is (round-trip? [:class " " [" " "["]]))
  (is (round-trip? [:ctrl "A"]))
  (is (round-trip? [:class "   - "]))
  (is (round-trip? [:alt "  " [:capture " " :escape]]))
  (is (round-trip? :whitespace))
  (is (round-trip? [:? [:? "x"]]))
  (is (round-trip? [:cat "  " [:class " " :non-whitespace]]))
  (is (round-trip? [:cat "-" [:repeat [:repeat "x" 0] 0]])))
