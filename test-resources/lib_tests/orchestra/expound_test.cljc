(ns orchestra.expound-test
  (:require #?@(:clj [[clojure.test :refer :all]
                      [clojure.spec.alpha :as s]
                      [orchestra.spec.test :as st]
                      [orchestra.core :refer [defn-spec]]]

              :cljs [[cljs.test
                      :refer-macros [deftest testing is use-fixtures]]
                     [cljs.spec.alpha :as s]
                     [orchestra-cljs.spec.test :as st]
                     [orchestra.core :refer-macros [defn-spec]]])

            [expound.alpha :as expound]))

(defn-spec instrument-fixture any?
  [f fn?]
  (st/unstrument)
  (st/instrument)
  (binding [s/*explain-out* expound/printer]
    (f)))
(use-fixtures :each instrument-fixture)

(defn-spec expound' true?
  [blah string?]
  true)

(deftest expound
  (testing "Pretty printed"
    (try
      (expound' 42)
      (catch #?(:clj RuntimeException :cljs :default) e
        (is (some? (-> (ex-data e)
                       s/explain-out
                       with-out-str
                       (clojure.string/includes? "-- Spec failed --"))))))))
