(ns clojure-csv.test.utils
  "Some whitebox testing of the private utility functions used in core."
  (:import [java.io StringReader])
  ;; BB-TEST-PATCH: Had to require since use caused conflict which bb failed on
  (:require [clojure-csv.core])
  (:use clojure.test
        clojure.java.io))

(def default-options {:delimiter \, :quote-char \"
                      :strict false :end-of-line nil})

(deftest eol-at-reader-pos?
  ;; Testing the private function to check for EOLs
  (is (= true (#'clojure-csv.core/eol-at-reader-pos? (StringReader. "\n") nil)))
  (is (= true (#'clojure-csv.core/eol-at-reader-pos? (StringReader. "\r\n")
                                                     nil)))
  (is (= true (#'clojure-csv.core/eol-at-reader-pos? (StringReader. "\nabc")
                                                     nil)))
  (is (= true (#'clojure-csv.core/eol-at-reader-pos? (StringReader. "\r\nabc")
                                                     nil)))
  (is (= false (#'clojure-csv.core/eol-at-reader-pos? (StringReader. "\r\tabc")
                                                      nil)))
  ;; Testing for user-specified EOLs
  (is (= true (#'clojure-csv.core/eol-at-reader-pos? (StringReader. "abc")
                                                     "abc")))
  (is (= true (#'clojure-csv.core/eol-at-reader-pos? (StringReader. "abcdef")
                                                     "abc")))
  (is (= false (#'clojure-csv.core/eol-at-reader-pos? (StringReader. "ab")
                                                      "abc"))))

(deftest skip-past-eol
  (is (= (int \c)
         (let [rdr (StringReader. "\nc")]
           (#'clojure-csv.core/skip-past-eol rdr)
           (.read rdr))))
  (is (= (int \c)
         (let [rdr (StringReader. "\r\nc")]
           (#'clojure-csv.core/skip-past-eol rdr)
           (.read rdr))))
  (is (= (int \c)
         (let [rdr (StringReader. "QQQc")]
           (#'clojure-csv.core/skip-past-eol rdr "QQQ")
           (.read rdr)))))

(deftest read-unquoted-field
  (let [{:keys [delimiter quote-char strict end-of-line]} default-options]
    (is (= "abc" (#'clojure-csv.core/read-unquoted-field
                  (StringReader. "abc,def")
                  delimiter quote-char strict end-of-line)))
    (is (= "abc" (#'clojure-csv.core/read-unquoted-field
                  (StringReader. "abc")
                  delimiter quote-char strict end-of-line)))
    (is (= "abc" (#'clojure-csv.core/read-unquoted-field
                  (StringReader. "abc\n")
                  delimiter quote-char strict end-of-line)))
    (is (= "abc" (#'clojure-csv.core/read-unquoted-field
                  (StringReader. "abc\r\n")
                  delimiter quote-char strict end-of-line)))
    (is (= "abc" (#'clojure-csv.core/read-unquoted-field
                  (StringReader. "abcQQQ")
                  delimiter quote-char strict "QQQ")))
    (is (= "abc\n" (#'clojure-csv.core/read-unquoted-field
                    (StringReader. "abc\nQQQ")
                    delimiter quote-char strict "QQQ")))
    (is (= "abc\"" (#'clojure-csv.core/read-unquoted-field
                    (StringReader. "abc\",")
                    delimiter quote-char strict end-of-line)))
    (is (= "" (#'clojure-csv.core/read-unquoted-field
               (StringReader. ",,,")
               delimiter quote-char strict end-of-line)))
    (is (thrown? java.lang.Exception
                 (#'clojure-csv.core/read-unquoted-field
                  (StringReader. "abc\",")
                  delimiter quote-char true end-of-line)))))

(deftest escaped-quote-at-reader-pos?
  (is (= true (#'clojure-csv.core/escaped-quote-at-reader-pos?
               (StringReader. "\"\"")
               (int \"))))
  (is (= true (#'clojure-csv.core/escaped-quote-at-reader-pos?
               (StringReader. "\"\"abc")
               (int \"))))
  (is (= false (#'clojure-csv.core/escaped-quote-at-reader-pos?
                (StringReader. "\"abc")
                (int \"))))
  (is (= false (#'clojure-csv.core/escaped-quote-at-reader-pos?
                (StringReader. "abc")
                (int \")))))

(deftest read-quoted-field
  (let [{:keys [delimiter quote-char strict]} default-options
        delimiter (int delimiter)
        quote-char (int quote-char)]
    (is (= "abc" (#'clojure-csv.core/read-quoted-field
                  (StringReader. "\"abc\"")
                  delimiter quote-char strict)))
    (is (= "abc" (#'clojure-csv.core/read-quoted-field
                  (StringReader. "\"abc\",def")
                  delimiter quote-char strict)))
    (is (= "ab\"c" (#'clojure-csv.core/read-quoted-field
                    (StringReader. "\"ab\"\"c\"")
                    delimiter quote-char strict)))
    (is (= "ab\nc" (#'clojure-csv.core/read-quoted-field
                    (StringReader. "\"ab\nc\"")
                    delimiter quote-char strict)))
    (is (= "ab,c" (#'clojure-csv.core/read-quoted-field
                   (StringReader. "\"ab,c\"")
                   delimiter quote-char strict)))
    (is (thrown? java.lang.Exception
                 (#'clojure-csv.core/read-quoted-field
                  (StringReader. "\"abc")
                  delimiter quote-char true)))))
