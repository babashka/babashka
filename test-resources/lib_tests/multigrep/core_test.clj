(ns multigrep.core-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [multigrep.core :as grep])
  (:import (java.io StringReader)))

(def lorem-ipsum "Lorem ipsum dolor sit amet, consectetur adipiscing
elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua.
Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut
aliquip ex ea commodo consequat. Duis aute irure dolor in reprehenderit
in voluptate velit esse cillum dolore eu fugiat nulla pariatur.
Excepteur sint occaecat cupidatat non proident, sunt in culpa qui
officia deserunt mollit anim id est laborum.")

(deftest single-regex-test
  (testing "single regex, single text source"
    (let [result (grep/grep #"t[,.]" (StringReader. lorem-ipsum))]
      (is (= [1 2 4 6] (mapv :line-number result))))))

(deftest multi-regex-test
  (testing "multiple regexes, multiple text sources"
    (let [result (grep/grep [#"t[,.]" #"s\s"] [(StringReader. lorem-ipsum) (io/resource "multigrep/haiku.txt")])]
      (is (= 8 (count result))))))
