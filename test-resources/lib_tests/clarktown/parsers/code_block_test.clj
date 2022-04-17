(ns clarktown.parsers.code-block-test
  (:require
    ;; require clojure.string to accomodate line break hack below
    [clojure.string :as str]
    [clojure.test :refer [deftest testing is]]
    [clojure.java.io :as io]
    [clarktown.parsers.code-block :as code-block]))

;; BB-TEST-PATCH: change paths to match folder structure (and copy resource files)
;; BB-TEST-PATCH: use split-lines to make tests platform-agnostic 
(deftest code-block-test
  (testing "Code block with language specification"
    (is (= (str/split-lines (slurp (io/file (io/resource "clarktown/parsers/code_block_result.html"))))
          (str/split-lines (code-block/render (slurp (io/file (io/resource "clarktown/parsers/code_block.md"))) nil)))))

  (testing "Code block with NO language specification"
    (is (= (str/split-lines (slurp (io/file (io/resource "clarktown/parsers/code_block_no_language_result.html"))))
          (str/split-lines (code-block/render (slurp (io/file (io/resource "clarktown/parsers/code_block_no_language.md"))) nil))))))
