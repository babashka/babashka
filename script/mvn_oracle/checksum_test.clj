#!/usr/bin/env bb
;; babashka.impl.mvn.http against ChecksumUtilTest: how a checksum sidecar
;; is read, and which sidecars a download accepts.
;; Run: ./bb -cp resources/src/babashka script/mvn_oracle/checksum_test.clj
(ns checksum-test
  (:require [babashka.fs :as fs]
            [babashka.impl.mvn.http :as http]
            [clojure.test :as t :refer [deftest is testing]]))

(def sums
  {"SHA-512" "3d5ad0e4a2bf0b9c7d6ae3a5e8ea0cc0ab16bba0cd1a8b4b6b5ae3e8f6a8f0b5bd4a2a1a1a66b7a5c0f7ce6e0e7a1f3d2d2c1b7b7f8e3a4f6d7c8a9b0c1d2e3f4"
   "SHA-256" "1d3f23a1e39b5da3a48a2d6b8c9e3f0a4a5b6c7d8e9f0a1b2c3d4e5f60718293"
   "SHA-1" "b2ea6d4d1c88d2a4b1c9d3d8e1f5a3f7c2b4d6e8"
   "MD5" "5f4dcc3b5aa765d61d8327deb882cf99"})

(def read-checksum #'http/parse-checksum)

(deftest read-test
  (doseq [[algorithm sum] sums]
    (testing algorithm
      (is (= sum (read-checksum sum)))
      (is (= sum (read-checksum (str sum "\n"))))))
  (testing "leading blank lines are skipped"
    (is (= (sums "MD5") (read-checksum (str "\n  \n" (sums "MD5") "\n"))))))

(deftest read-spaces-test
  (testing "name = sum"
    (is (= (sums "SHA-512") (read-checksum (str "sha512-checksum = " (sums "SHA-512")))))
    (is (= (sums "SHA-256") (read-checksum (str "sha256-checksum = " (sums "SHA-256")))))
    (is (= (sums "SHA-1") (read-checksum (str "sha1-checksum = " (sums "SHA-1"))))))
  (testing "sum file"
    (is (= (sums "MD5") (read-checksum (str (sums "MD5") " test"))))))

(deftest read-empty-test
  (is (= "" (read-checksum ""))))

(defn- download
  "Downloads a/b.jar from a file: repository with the given sidecars,
  failing on a checksum problem."
  [dir sidecars]
  (let [repo (fs/file dir "repo")
        jar (fs/file repo "a" "b.jar")]
    (fs/create-dirs (fs/parent jar))
    (spit jar "jar contents")
    (doseq [[ext text] sidecars]
      (spit (fs/file repo "a" (str "b.jar" ext)) text))
    (http/download! (str (.toURI (fs/file repo)) "a/b.jar") (fs/file dir "out.jar") {:checksum :fail :label "b.jar"})))

(defn- hex-digest [algorithm ^String text]
  (let [md (java.security.MessageDigest/getInstance algorithm)]
    (.update md (.getBytes text "UTF-8"))
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md)))))

(deftest download-test
  (let [sha1 (hex-digest "SHA-1" "jar contents")
        md5 (hex-digest "MD5" "jar contents")]
    (testing "a sha1 in the name = sum form"
      (fs/with-temp-dir [dir {}]
        (is (some? (download dir {".sha1" (str "sha1-checksum = " sha1)})))))
    (testing "an uppercase sha1 followed by the file name"
      (fs/with-temp-dir [dir {}]
        (is (some? (download dir {".sha1" (str (.toUpperCase sha1) "  b.jar")})))))
    (testing "md5 when the repository publishes no sha1"
      (fs/with-temp-dir [dir {}]
        (is (some? (download dir {".md5" md5})))))
    (testing "a wrong sha1 fails, whatever the md5 says"
      (fs/with-temp-dir [dir {}]
        (is (thrown-with-msg? Exception #"Checksum validation failed for b.jar, expected 0{40} but is"
                              (download dir {".sha1" (apply str (repeat 40 "0")) ".md5" md5})))))
    (testing "no sidecar at all"
      (fs/with-temp-dir [dir {}]
        (is (thrown-with-msg? Exception #"no checksums available"
                              (download dir {})))))))

(let [{:keys [fail error]} (t/run-tests 'checksum-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
