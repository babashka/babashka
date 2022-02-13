(ns io.aviso.binary-test
  "Tests for the io.aviso.binary namespace."
  (:use io.aviso.binary
        clojure.test)
  (:import (java.nio ByteBuffer)))

(defn ^:private format-string-as-byte-array [str]
  (format-binary (.getBytes str)))

(deftest format-byte-array-test

  (are [input expected]
    (= expected (format-string-as-byte-array input))

    "Hello" "0000: 48 65 6C 6C 6F\n"

    "This is a longer text that spans to a second line."
    "0000: 54 68 69 73 20 69 73 20 61 20 6C 6F 6E 67 65 72 20 74 65 78 74 20 74 68 61 74 20 73 70 61 6E 73\n0020: 20 74 6F 20 61 20 73 65 63 6F 6E 64 20 6C 69 6E 65 2E\n"))

(deftest format-string-as-byte-data
  (are [input expected]
    (= expected (format-binary input))
    "" ""

    "Hello" "0000: 48 65 6C 6C 6F\n"

    "This is a longer text that spans to a second line."

    "0000: 54 68 69 73 20 69 73 20 61 20 6C 6F 6E 67 65 72 20 74 65 78 74 20 74 68 61 74 20 73 70 61 6E 73\n0020: 20 74 6F 20 61 20 73 65 63 6F 6E 64 20 6C 69 6E 65 2E\n"))

(deftest nil-is-an-empty-data
  (is (= (format-binary nil) "")))

(deftest byte-buffer
  (let [bb (ByteBuffer/wrap (.getBytes "Duty Now For The Future" "UTF-8"))]
    (is (= "0000: 44 75 74 79 20 4E 6F 77 20 46 6F 72 20 54 68 65 20 46 75 74 75 72 65\n"
           (format-binary bb)))

    (is (= "0000: 44 75 74 79\n"
           (-> bb
               (.position 5)
               (.limit 9)
               format-binary)))

    (is (= "0000: 46 6F 72\n"
           (-> bb
               (.position 9)
               (.limit 12)
               .slice
               format-binary)))

    ))
