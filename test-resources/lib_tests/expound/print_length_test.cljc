(ns expound.print-length-test
  (:require [clojure.test :as ct :refer [is deftest testing]]
            [clojure.spec.alpha :as s]
            [expound.alpha]
            [clojure.string :as string]))

(def the-value (range 10))
;; Fails on the last element of the range
(def the-spec (s/coll-of #(< % 9)))
(def the-explanation (s/explain-data the-spec the-value))

(deftest print-length-test
  (testing "Expound works even in face of a low `*print-length*` and `*print-level*`, without throwing exceptions.
See https://github.com/bhb/expound/issues/217"
    (doseq [length [1 5 100 *print-length*]
            level [1 5 100 *print-level*]
            ;; Note that the `is` resides outside of the `binding`. Else test output itself can be affected.
            :let [v (binding [*print-length* length
                              *print-level* level]
                      (with-out-str
                        (expound.alpha/printer the-explanation)))]]
      ;; Don't make a particularly specific test assertion, since a limited print-length isn't necessarily realistic/usual:
      (is (not (string/blank? v))))))
