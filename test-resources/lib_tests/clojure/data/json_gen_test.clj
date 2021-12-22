(ns clojure.data.json-gen-test
  (:require [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as sgen]
            [clojure.spec.test.alpha :as stest]
            [clojure.test :refer :all]))

(s/def ::json-number
  (s/with-gen
    number?
    #(sgen/one-of [(sgen/large-integer) (sgen/double* {:infinite? false :NaN? false})])))

(s/def ::json-scalar (s/or :boolean boolean?
                           :number ::json-number
                           :string string?
                           :nil nil?))

(s/def ::json-value (s/or :object ::json-object
                          :array ::json-array
                          :scalar ::json-scalar))

(s/def ::json-array (s/coll-of ::json-value :gen-max 12))
(s/def ::json-object (s/map-of string? ::json-value
                               :gen-max 10))

(s/fdef json/write-str
  :args (s/cat :json ::json-value)
  :ret string?
  :fn #(= (->> % :args :json (s/unform ::json-value))
          (json/read-str (-> % :ret))))

(deftest roundtrip
  (let [results (stest/check `json/write-str)]
    (is (every? nil? (mapv :failure results)))))
