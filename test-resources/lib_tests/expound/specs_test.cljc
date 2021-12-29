(ns expound.specs-test
  (:require [expound.specs]
            [clojure.spec.alpha :as s]
            [clojure.test :as ct :refer [is deftest use-fixtures]]
            [expound.test-utils :as test-utils]
            [expound.alpha :as expound]))

(use-fixtures :once
  test-utils/check-spec-assertions
  test-utils/instrument-all)

(deftest provided-specs
  (binding [s/*explain-out* (expound/custom-printer {:print-specs? false})]
    (is (= "-- Spec failed --------------------

  1

should be a keyword with no namespace

-------------------------
Detected 1 error
"
           (s/explain-str :expound.specs/simple-kw 1)))
    (doseq [kw expound.specs/public-specs]
      (is (some? (s/get-spec kw)) (str "Failed to find spec for keyword " kw))
      (is (some? (expound/error-message kw)) (str "Failed to find error message for keyword " kw)))))
