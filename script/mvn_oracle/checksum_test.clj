#!/usr/bin/env bb
;; babashka.impl.mvn.http against ChecksumUtilTest: how a checksum sidecar
;; is read, and which sidecars a download accepts.
;; Run: ./bb -cp resources/src/babashka script/mvn_oracle/checksum_test.clj
(ns checksum-test
  (:require [babashka.fs :as fs]
            [babashka.impl.mvn.http :as http]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [org.httpkit.server :as server]))

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
  "Downloads a/b.jar from a file: repository with the given sidecars.
  policy is the checksum policy, :fail by default."
  ([dir sidecars] (download dir sidecars :fail))
  ([dir sidecars policy]
   (let [repo (fs/file dir "repo")
         jar (fs/file repo "a" "b.jar")]
     (fs/create-dirs (fs/parent jar))
     (spit jar "jar contents")
     (doseq [[ext text] sidecars]
       (spit (fs/file repo "a" (str "b.jar" ext)) text))
     (http/download! (str (.toURI (fs/file repo)) "a/b.jar") (fs/file dir "out.jar") {:checksum policy :label "b.jar"}))))

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

(deftest sidecar-test
  (let [sha1 (hex-digest "SHA-1" "jar contents")
        md5 (hex-digest "MD5" "jar contents")
        zeros (apply str (repeat 40 "0"))
        sidecar #(let [file (fs/file %1 (str "out.jar" %2))]
                   (when (fs/exists? file) (slurp file)))]
    (testing "a verified download writes the published sha1 unchanged"
      (fs/with-temp-dir [dir {}]
        (download dir {".sha1" (str (.toUpperCase sha1) "  b.jar") ".md5" md5})
        (is (= (.toUpperCase sha1) (sidecar dir ".sha1")))
        (is (nil? (sidecar dir ".md5")))))
    (testing "a verified download writes the md5 if no sha1 is published"
      (fs/with-temp-dir [dir {}]
        (download dir {".md5" md5})
        (is (= md5 (sidecar dir ".md5")))
        (is (nil? (sidecar dir ".sha1")))))
    (testing ":fail writes neither file nor checksum for a wrong sha1"
      (fs/with-temp-dir [dir {}]
        (is (thrown? Exception (download dir {".sha1" zeros})))
        (is (not (fs/exists? (fs/file dir "out.jar"))))
        (is (nil? (sidecar dir ".sha1")))))
    (testing ":warn keeps the file and writes the wrong published sha1"
      (fs/with-temp-dir [dir {}]
        (binding [*err* (java.io.StringWriter.)]
          (download dir {".sha1" zeros} :warn))
        (is (fs/exists? (fs/file dir "out.jar")))
        (is (= zeros (sidecar dir ".sha1")))))
    (testing ":ignore writes no checksum"
      (fs/with-temp-dir [dir {}]
        (download dir {".sha1" sha1} :ignore)
        (is (fs/exists? (fs/file dir "out.jar")))
        (is (nil? (sidecar dir ".sha1")))))))

(deftest retry-test
  (let [requests (atom [])
        contents (atom "first")
        stop (server/run-server (fn [{:keys [uri]}]
                                  (swap! requests conj uri)
                                  (case uri
                                    "/a/b.jar" (let [body @contents]
                                                 (reset! contents "jar contents")
                                                 {:status 200 :body body})
                                    "/a/b.jar.sha1" {:status 200 :body (hex-digest "SHA-1" "jar contents")}
                                    "/a/c.jar" {:status 200 :body "jar contents"}
                                    {:status 404}))
                                {:port 0 :legacy-return-value? false})
        base (str "http://localhost:" (server/server-port stop) "/a/")
        url (str base "b.jar")]
    (try
      (testing "a checksum mismatch downloads the file once more"
        (fs/with-temp-dir [dir {}]
          (let [err (java.io.StringWriter.)]
            (binding [*err* err]
              (http/download! url (fs/file dir "out.jar") {:checksum :fail :label "b.jar"}))
            (is (= "jar contents" (slurp (fs/file dir "out.jar"))))
            (is (= 2 (count (filter #{"/a/b.jar"} @requests))))
            (is (str/includes? (str err) "Checksum validation failed for b.jar")))))
      (testing "a missing checksum does not download the file again"
        (fs/with-temp-dir [dir {}]
          (reset! requests [])
          (binding [*err* (java.io.StringWriter.)]
            (http/download! (str base "c.jar") (fs/file dir "out.jar") {:checksum :warn :label "c.jar"}))
          (is (fs/exists? (fs/file dir "out.jar")))
          (is (= 1 (count (filter #{"/a/c.jar"} @requests))))))
      (finally
        (server/server-stop! stop)))))

(defn- with-server
  "Calls f with the base URL of a server running handler and an atom of the requested URIs."
  [handler f]
  (let [requests (atom [])
        stop (server/run-server (fn [{:keys [uri] :as request}]
                                  (swap! requests conj uri)
                                  (handler request))
                                {:port 0 :legacy-return-value? false})]
    (try
      (f (str "http://localhost:" (server/server-port stop) "/") requests)
      (finally
        (server/server-stop! stop)))))

(defn- fetch-jar
  "Downloads b.jar from a server that sends headers with it and publishes sidecars.
  Returns the requested URIs, the written sidecars by extension, and stderr."
  [headers sidecars policy]
  (fs/with-temp-dir [dir {}]
    (with-server
      (fn [{:keys [uri]}]
        (cond
          (= "/b.jar" uri) {:status 200 :headers headers :body "jar contents"}
          (contains? sidecars uri) (let [answer (get sidecars uri)]
                                     (if (number? answer) {:status answer} {:status 200 :body answer}))
          :else {:status 404}))
      (fn [base requests]
        (let [err (java.io.StringWriter.)]
          (binding [*err* err]
            (http/download! (str base "b.jar") (fs/file dir "out.jar") {:checksum policy :label "b.jar"}))
          {:requests @requests
           :sidecars (into {} (keep (fn [ext]
                                      (let [file (fs/file dir (str "out.jar" ext))]
                                        (when (fs/exists? file) [ext (slurp file)]))))
                           [".sha1" ".md5"])
           :err (str err)})))))

(deftest included-checksum-test
  (let [sha1 (hex-digest "SHA-1" "jar contents")
        md5 (hex-digest "MD5" "jar contents")
        ones (apply str (repeat 40 "1"))]
    (testing "x-checksum-sha1 verifies the file without a request for b.jar.sha1"
      (is (= {:requests ["/b.jar"] :sidecars {".sha1" sha1} :err "Downloading: b.jar from \n"}
             (fetch-jar {"x-checksum-sha1" sha1} {} :fail))))
    (testing "x-checksum-sha1 takes precedence over x-checksum-md5"
      (is (= {".sha1" sha1} (:sidecars (fetch-jar {"x-checksum-md5" md5 "x-checksum-sha1" sha1} {} :fail)))))
    (testing "x-checksum-md5 alone verifies the file and writes b.jar.md5"
      (is (= {:requests ["/b.jar"] :sidecars {".md5" md5}}
             (select-keys (fetch-jar {"x-checksum-md5" md5} {} :fail) [:requests :sidecars]))))
    (testing "x-goog-meta-checksum-sha1 verifies the file"
      (is (= {:requests ["/b.jar"] :sidecars {".sha1" sha1}}
             (select-keys (fetch-jar {"x-goog-meta-checksum-sha1" sha1} {} :fail) [:requests :sidecars]))))
    (testing "an ETag with SHA1{...} verifies the file"
      (is (= {:requests ["/b.jar"] :sidecars {".sha1" sha1}}
             (select-keys (fetch-jar {"ETag" (str "\"{SHA1{" sha1 "}}\"")} {} :fail) [:requests :sidecars]))))
    (testing "x-checksum-sha1 takes precedence over the ETag"
      (is (= {".sha1" ones}
             (:sidecars (fetch-jar {"ETag" (str "\"{SHA1{" sha1 "}}\"") "x-checksum-sha1" ones} {} :warn)))))
    (testing ":warn downloads twice for a wrong x-checksum-sha1 and never requests b.jar.sha1"
      (is (= {:requests ["/b.jar" "/b.jar"] :sidecars {".sha1" ones}}
             (select-keys (fetch-jar {"x-checksum-sha1" ones} {"/b.jar.sha1" sha1} :warn) [:requests :sidecars]))))
    (testing ":fail throws for a wrong x-checksum-sha1"
      (is (thrown-with-msg? Exception #"expected 1{40} but is"
                            (fetch-jar {"x-checksum-sha1" ones} {"/b.jar.sha1" sha1} :fail))))))

(deftest failed-checksum-request-test
  (let [md5 (hex-digest "MD5" "jar contents")]
    (testing "HTTP 500 for b.jar.sha1 verifies the file against b.jar.md5"
      (is (= {".md5" md5} (:sidecars (fetch-jar {} {"/b.jar.sha1" 500 "/b.jar.md5" md5} :fail)))))
    (testing ":warn keeps the file if both checksum requests answer HTTP 500"
      (let [{:keys [sidecars err]} (fetch-jar {} {"/b.jar.sha1" 500 "/b.jar.md5" 500} :warn)]
        (is (= {} sidecars))
        (is (str/includes? err "Checksum validation failed for b.jar, no checksums available"))))
    (testing ":fail throws if both checksum requests answer HTTP 500"
      (is (thrown-with-msg? Exception #"no checksums available"
                            (fetch-jar {} {"/b.jar.sha1" 500 "/b.jar.md5" 500} :fail))))))

(let [{:keys [fail error]} (t/run-tests 'checksum-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
