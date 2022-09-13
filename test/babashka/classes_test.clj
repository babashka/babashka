(ns babashka.classes-test
  (:require [babashka.test-utils :as tu]
            [clojure.edn :as edn]
            [clojure.test :as t :refer [deftest is testing]]))

(defn bb
  [& args]
  (edn/read-string (apply tu/bb nil (map pr-str args))))

(deftest all-classes-test
  (is (true? (bb '(let [classes (babashka.classes/all-classes)] (and (seq classes) (every? class? classes)))))))
