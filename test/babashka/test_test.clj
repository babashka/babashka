(ns babashka.test-test
  (:require
   [babashka.test-utils :as tu]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :as t :refer [deftest is]]
   [clojure.string :as str]))

(defn bb [& args]
  (apply tu/bb nil (map str args)))

(deftest deftest-test
  (is (str/includes?
       (bb "(require '[clojure.test :as t]) (t/deftest foo (t/is (= 4 5))) (foo)")
       "expected: (= 4 5)\n  actual: false\n")))
