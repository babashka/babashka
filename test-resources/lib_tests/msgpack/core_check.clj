(ns msgpack.core-check
  (:require [msgpack.core :as msg]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]))

; NaN is never equal to itself
(defn- not-nan [x]
  (not (Double/isNaN x)))

(defn- pack-and-unpack [x]
  (msg/unpack (msg/pack x)))

(defspec ints-round-trip 100
  (prop/for-all [x gen/int]
                (= (pack-and-unpack x) x)))

(defspec floats-round-trip 100
  (prop/for-all [x (gen/such-that not-nan gen/double)]
                (= (pack-and-unpack x) x)))

(defspec bytes-round-trip 100
  (prop/for-all [x gen/bytes]
                (let [bytes (pack-and-unpack x)]
                  (and (instance? (Class/forName "[B") bytes)
                       (= (seq bytes) (seq x))))))

(defn- box [bytes]
  (let [a (java.lang.reflect.Array/newInstance Byte (count bytes))]
    (doseq [[i b] (map-indexed vector (seq bytes))]
      (java.lang.reflect.Array/set a i b))
    a))

(defspec boxed-bytes-round-trip 100
  (prop/for-all [x (gen/fmap box gen/bytes)]
                (let [bytes (pack-and-unpack x)]
                  (and (instance? (Class/forName "[B") bytes)
                       (= (seq bytes) (seq x))))))

(defspec strings-round-trip 100
  (prop/for-all [x gen/string]
                (= (pack-and-unpack x) x)))

(def ^:private vector-of-maps (gen/vector (gen/map gen/int gen/string)))

(defspec vectors-round-trip 20
  (prop/for-all [x vector-of-maps]
                (= (pack-and-unpack x) x)))

(defspec maps-round-trip 20
  (prop/for-all [x (gen/map gen/string vector-of-maps)]
                (= (pack-and-unpack x) x)))
