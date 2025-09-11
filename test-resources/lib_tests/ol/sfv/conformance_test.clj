;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: MIT
(ns ol.sfv.conformance-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.pprint :as pp]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            ;; BB_TEST_PATCH: changed to cheshire
            [cheshire.core :as json]
            [ol.sfv.impl :as impl]
            ;; BB_TEST_PATCH: b32 support
            [alphabase.base32 :as b32]))

(defn load-test-cases
  "Load test cases from a JSON file in the structured-field-tests directory"
  [filename]
  (with-open [reader (io/reader (io/resource (str "fixtures/" filename)))]
    ;; BB_TEST_PATCH: changed to cheshire
    (json/parse-stream-strict reader true)))

(defn load-serialization-test-cases
  "Load serialization test cases from a JSON file in the serialisation-tests directory"
  [filename]
  (with-open [reader (io/reader (io/resource (str "fixtures/serialisation-tests/" filename)))]
    ;; BB_TEST_PATCH: changed to cheshire
    (json/parse-stream-strict reader true)))

(defn base32->bytes
  "Decode Base32 to bytes using Apache Commons Codec"
  [base32-str]
  ;; BB_TEST_PATCH
  (b32/decode base32-str)
  #_(if (str/blank? base32-str)
    (byte-array 0)
    (let [codec (org.apache.commons.codec.binary.Base32.)]
      (.decode codec ^String base32-str))))

(defn expected-value->clojure
  "Convert the expected value format from the test suite to our Clojure representation"
  [value]
  (cond
    ;; Handle tokens with __type metadata
    (and (map? value) (= (:__type value) "token"))
    {:type :token :value (:value value)}

    ;; Handle binary with __type metadata - decode base32 to actual bytes for comparison
    (and (map? value) (= (:__type value) "binary"))
    {:type :bytes :value (base32->bytes (:value value))}

    ;; Handle display strings with __type metadata
    (and (map? value) (= (:__type value) "displaystring"))
    {:type :dstring :value (:value value)}

    ;; Handle dates with __type metadata
    (and (map? value) (= (:__type value) "date"))
    {:type :date :value (:value value)}

    ;; Handle bare primitives
    (string? value) {:type :string :value value}
    (integer? value) {:type :integer :value value}
    (float? value) {:type :decimal :value (bigdec value)} ; Convert to BigDecimal for consistency
    (true? value) {:type :boolean :value true}
    (false? value) {:type :boolean :value false}

    ;; Handle vectors (parameters or inner lists)
    (vector? value)
    (mapv expected-value->clojure value)

    ;; Pass through anything else
    :else value))

(defn expected-params->clojure
  "Convert parameter format from test suite to our representation"
  [params]
  (mapv (fn [[k v]] [k (expected-value->clojure v)]) params))

(defn expected-item->clojure
  "Convert an item format from test suite to our representation"
  [[bare-item params]]
  {:type :item
   :bare (expected-value->clojure bare-item)
   :params (expected-params->clojure params)})

(defn expected-inner-list->clojure
  "Convert an inner list format from test suite to our representation"
  [[items params]]
  {:type :inner-list
   :items (mapv expected-item->clojure items)
   :params (expected-params->clojure params)})

(defn expected-list->clojure
  "Convert a list format from test suite to our representation"
  [expected]
  {:type :list
   :members (mapv (fn [member]
                    (let [[first-elem] member]
                      (if (vector? first-elem)
                        ;; It's an inner list
                        (expected-inner-list->clojure member)
                        ;; It's a regular item
                        (expected-item->clojure member))))
                  expected)})

(defn expected-dict->clojure
  "Convert a dictionary format from test suite to our representation"
  [expected]
  {:type :dict
   :entries (mapv (fn [[key [value params]]]
                    [key (if (vector? value)
                           ;; It's an inner list
                           {:type :inner-list
                            :items (mapv expected-item->clojure value)
                            :params (expected-params->clojure params)}
                           ;; It's a regular item  
                           {:type :item
                            :bare (expected-value->clojure value)
                            :params (expected-params->clojure params)})])
                  expected)})

(defn norm [data]
  (walk/postwalk #(if (bytes? %) (seq %) %) data))

(defn run-parse-test
  "Run a single parsing test case"
  [{:keys [name raw header_type expected must_fail] :as t}]
  (testing (str "Parse test: " name)
    (println "conformance test definition:")
    (pp/pprint t)
    (let [input (if (= 1 (count raw))
                  (first raw)
                  (impl/combine-field-lines raw))]
      (if must_fail
        (is (thrown? Exception (impl/parse header_type input))
            (str "Expected parse to fail for: " name))
        (let [result (impl/parse header_type input)
              expected-clojure (case header_type
                                 "item" (expected-item->clojure expected)
                                 "list" (expected-list->clojure expected)
                                 "dictionary" (expected-dict->clojure expected))]
          (is (= (norm expected-clojure) (norm result))
              (str "Parse result mismatch for: " name
                   "\nExpected: " (pr-str expected-clojure)
                   "\nActual: " (pr-str result))))))))

(defn run-serialize-test
  "Run a single serialization test case"
  [{:keys [name header_type expected canonical must_fail] :as t}]
  (testing (str "Serialize test: " name)
    (println "conformance test definition:")
    (pp/pprint t)
    (let [input-clojure (case header_type
                          "item" (expected-item->clojure expected)
                          "list" (expected-list->clojure expected)
                          "dictionary" (expected-dict->clojure expected))]
      (if must_fail
        (is (thrown? Exception (impl/serialize input-clojure))
            (str "Expected serialize to fail for: " name))
        (let [result (impl/serialize input-clojure)
              expected-canonical (if canonical
                                   (if (= 1 (count canonical))
                                     (first canonical)
                                     (str/join ", " canonical))
                                   nil)]
          (if expected-canonical
            (is (= expected-canonical result)
                (str "Serialize result mismatch for: " name
                     "\nExpected: " (pr-str expected-canonical)
                     "\nActual: " (pr-str result)))
            (is (string? result)
                (str "Serialize should return a string for: " name))))))))

(defn run-serialize-conformance-test
  "Run a round-trip serialization test for a conformance test case"
  [{:keys [name raw header_type canonical] :as t}]
  (testing (str "Serialize conformance test: " name)
    (println "conformance test definition:")
    (pp/pprint t)
    (let [input (if (= 1 (count raw))
                  (first raw)
                  (impl/combine-field-lines raw))
          parsed-result (impl/parse header_type input)
          serialized-result (impl/serialize parsed-result)
          expected-output (if canonical
                            (if (= 1 (count canonical))
                              (first canonical)
                              (str/join ", " canonical))
                            input)]
      (is (= expected-output serialized-result)
          (str "Round-trip serialization mismatch for: " name
               "\nOriginal input: " (pr-str input)
               "\nExpected output: " (pr-str expected-output)
               "\nActual output: " (pr-str serialized-result))))))

(defn safe-test-name
  "Convert a test name to a safe symbol name"
  [name]
  (-> name
      (str/replace #"[^a-zA-Z0-9_]" "_")
      (str/replace #"_{2,}" "_")
      (str/replace #"^_|_$" "")))

(defn safe-filename
  "Convert a filename to a safe symbol name"
  [filename]
  (-> filename
      (str/replace #"\.json$" "")
      (str/replace #"-" "_")))

(defmacro run-conformance-tests
  "Generate test cases from a JSON file at compile time"
  [filename]
  (let [test-cases (load-test-cases filename)
        filename-safe (safe-filename filename)]
    `(do
       ~@(mapcat
          (fn [test-case]
            (let [name-safe (safe-test-name (:name test-case))
                  can-serialize (not (:must_fail test-case))]
              (cond-> [`(deftest ~(symbol (str filename-safe "_" name-safe))
                          (run-parse-test ~test-case))]
                can-serialize (conj `(deftest ~(symbol (str "serialize_" filename-safe "_" name-safe))
                                       (run-serialize-conformance-test ~test-case))))))
          test-cases))))

(defmacro run-serialization-tests
  "Generate serialization test cases from a JSON file at compile time"
  [filename]
  (let [test-cases (load-serialization-test-cases filename)
        filename-safe (safe-filename filename)]
    `(do
       ~@(for [test-case test-cases
               :let [name-safe (safe-test-name (:name test-case))]]
           `(deftest ~(symbol (str "serialize_" filename-safe "_" name-safe))
              (run-serialize-test ~test-case))))))

(def test-data-files ["binary.json"
                      "boolean.json"
                      "date.json"
                      "dictionary.json"
                      "display-string.json"
                      "examples.json"
                      "item.json"
                      "key-generated.json"
                      "large-generated.json"
                      "list.json"
                      "listlist.json"
                      "number-generated.json"
                      "number.json"
                      "param-dict.json"
                      "param-list.json"
                      "param-listlist.json"
                      "string-generated.json"
                      "string.json"
                      "token-generated.json"
                      "token.json"])

(def serialization-test-files ["string-generated.json"
                               "token-generated.json"
                               "key-generated.json"
                               "number.json"])

(defmacro generate-all-conformance-tests []
  `(do
     ~@(for [filename test-data-files]
         `(run-conformance-tests ~filename))))

(defmacro generate-all-serialization-tests []
  `(do
     ~@(for [filename serialization-test-files]
         `(run-serialization-tests ~filename))))

(generate-all-conformance-tests)
(generate-all-serialization-tests)
