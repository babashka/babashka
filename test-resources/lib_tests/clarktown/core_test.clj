(ns clarktown.core-test
  (:require
    ;; BB-TEST-PATCH: require clojure.string for split-lines patch below
    [clojure.string :as str]
    [clojure.test :refer [deftest testing is]]
    [clojure.java.io :as io]
    [clarktown.core :as core]))


(deftest overall-test
  (testing "Overall"
    ;; BB-TEST-PATCH: library uses hard-coded \n, so using split-lines for platform-agnostic testing
    ;; BB-TEST-PATCH: change file paths to match bb folder structure (and copy resource files)
    (is (= (str/split-lines (core/render (slurp (io/file (io/resource "clarktown/core.md")))))
          (str/split-lines (slurp (io/file (io/resource "clarktown/core_result.html"))))))))
