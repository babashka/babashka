#!/usr/bin/env bb
;; babashka.impl.mvn.http retries against maven-resolver-transport-http 1.9.27 and HttpClient 4.5.14.
;; Run: ./bb -cp resources/src/babashka script/mvn_oracle/retry_test.clj
(ns retry-test
  (:require [babashka.fs :as fs]
            [babashka.impl.mvn.http :as http]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [org.httpkit.server :as server]))

(defn- with-server
  "Calls f with the base URL of a server and an atom of the [method uri] requests.
  The server answers request n with the nth response of answers, and with b.jar after the last."
  [answers f]
  (let [requests (atom [])
        stop (server/run-server (fn [{:keys [uri request-method]}]
                                  (let [n (count (swap! requests conj [request-method uri]))]
                                    (or (get answers (dec n))
                                        {:status 200 :body "jar contents"})))
                                {:port 0 :legacy-return-value? false})]
    (try
      (f (str "http://localhost:" (server/server-port stop) "/") requests)
      (finally
        (server/server-stop! stop)))))

(defn- with-properties [props f]
  (let [old (into {} (map (fn [k] [k (System/getProperty k)])) (keys props))]
    (doseq [[k v] props] (System/setProperty k v))
    (try
      (f)
      (finally
        (doseq [[k v] old]
          (if v (System/setProperty k v) (System/clearProperty k)))))))

(defn- download
  "Downloads b.jar from a server with answers.
  Returns the request count and the file contents or the error message."
  ([answers] (download answers {}))
  ([answers opts]
   (fs/with-temp-dir [dir {}]
     (with-server answers
       (fn [base requests]
         (let [result (try (http/download! (str base "b.jar") (fs/file dir "b.jar")
                                           (merge {:checksum :ignore :label "b.jar" :repo-id "central"} opts))
                           (catch Exception e (ex-message e)))]
           {:requests (count @requests)
            :result (if (and (string? result) (fs/exists? result)) (slurp result) result)}))))))

(def ^:private too-many {:status 429 :headers {"Retry-After" "0"}})

(deftest status-test
  (testing "HTTP 429 sends the request again"
    (is (= {:requests 2 :result "jar contents"} (download [too-many]))))
  (testing "HTTP 503 sends the request again"
    (is (= {:requests 2 :result "jar contents"}
           (download [{:status 503 :headers {"Retry-After" "0"}}]))))
  (testing "HTTP 500 fails without a retry"
    (let [{:keys [requests result]} (download [{:status 500}])]
      (is (= 1 requests))
      (is (str/starts-with? result "HTTP 500"))))
  (testing "HTTP 429 on every request fails after 3 retries"
    (let [{:keys [requests result]} (download (vec (repeat 4 too-many)))]
      (is (= 4 requests))
      (is (str/starts-with? result "HTTP 429"))))
  (testing "HTTP 429 three times then 200 downloads the file"
    (is (= {:requests 4 :result "jar contents"} (download (vec (repeat 3 too-many)))))))

(deftest config-test
  (testing "retryHandler.count 0 sends one request"
    (with-properties {"aether.connector.http.retryHandler.count" "0"}
      #(is (= 1 (:requests (download [too-many]))))))
  (testing "retryHandler.count.central overrides retryHandler.count"
    (with-properties {"aether.connector.http.retryHandler.count" "0"
                      "aether.connector.http.retryHandler.count.central" "1"}
      #(is (= 2 (:requests (download (vec (repeat 3 too-many))))))))
  (testing "retryHandler.count.clojars leaves central at the default"
    (with-properties {"aether.connector.http.retryHandler.count.clojars" "0"}
      #(is (= 2 (:requests (download [too-many]))))))
  (testing "retryHandler.count many falls back to the default 3"
    (with-properties {"aether.connector.http.retryHandler.count" "many"}
      #(is (= 2 (:requests (download [too-many]))))))
  (testing "retryHandler.serviceUnavailable 500 retries HTTP 500 and not HTTP 429"
    (with-properties {"aether.connector.http.retryHandler.serviceUnavailable" "500"}
      (fn []
        (is (= 2 (:requests (download [{:status 500 :headers {"Retry-After" "0"}}]))))
        (is (= 1 (:requests (download [too-many])))))))
  (testing "retryHandler.count -1 fails the request"
    (with-properties {"aether.connector.http.retryHandler.count" "-1"}
      #(is (str/includes? (:result (download [])) "retryCount must be >= 0"))))
  (testing "retryHandler.serviceUnavailable 429,x fails the request"
    (with-properties {"aether.connector.http.retryHandler.serviceUnavailable" "429,x"}
      #(is (str/includes? (:result (download []))
                          "Illegal HTTP codes for aether.connector.http.retryHandler.serviceUnavailable (list of integers): 429,x")))))

(deftest wait-test
  (testing "Retry-After 301 fails without a retry"
    (is (= 1 (:requests (download [{:status 429 :headers {"Retry-After" "301"}}])))))
  (testing "Retry-After as a past HTTP date retries at once"
    (is (= 2 (:requests (download [{:status 429 :headers {"Retry-After" "Sun, 06 Nov 1994 08:49:37 GMT"}}])))))
  (testing "no Retry-After waits retryHandler.interval times the attempt"
    (with-properties {"aether.connector.http.retryHandler.interval" "100"}
      (fn []
        (let [start (System/currentTimeMillis)
              {:keys [requests]} (download [{:status 429} {:status 429}])]
          (is (= 3 requests))
          (is (<= 300 (- (System/currentTimeMillis) start)))))))
  (testing "a wait above retryHandler.intervalMax fails without a retry"
    (with-properties {"aether.connector.http.retryHandler.interval" "100"
                      "aether.connector.http.retryHandler.intervalMax" "150"}
      #(is (= 2 (:requests (download [{:status 429} {:status 429}])))))))

(def ^:private retry-wait #'http/retry-wait)

(deftest retry-after-test
  (let [config {:interval 5000 :interval-max 300000}
        in-a-minute (+ (System/currentTimeMillis) 60000)
        format-date (fn [pattern]
                      (.format (-> (java.time.format.DateTimeFormatter/ofPattern pattern java.util.Locale/US)
                                   (.withZone java.time.ZoneOffset/UTC))
                               (java.time.Instant/ofEpochMilli in-a-minute)))]
    (testing "Retry-After 2 waits 2000 ms"
      (is (= 2000 (retry-wait "2" 1 config))))
    (testing "an RFC 1123, RFC 1036 or asctime date waits until that date"
      (doseq [pattern ["EEE, dd MMM yyyy HH:mm:ss 'GMT'" "EEEE, dd-MMM-yy HH:mm:ss 'GMT'" "EEE MMM d HH:mm:ss yyyy"]]
        (is (< 58000 (retry-wait (format-date pattern) 1 config) 60001) pattern)))
    (testing "a past date waits 0 ms"
      (doseq [date ["Sun, 06 Nov 1994 08:49:37 GMT" "Sun, 06 Nov 1994 08:49:37 UTC"
                    "Wednesday, 06-Nov-24 08:49:37 GMT" "Sun Nov  6 08:49:37 1994"]]
        (is (= 0 (retry-wait date 1 config)) date)))
    (testing "Sunday, 06-Nov-94 reads as 6 November 2094 and exceeds retryHandler.intervalMax"
      (is (= (.toEpochMilli (java.time.Instant/parse "2094-11-06T08:49:37Z"))
             (#'http/parse-http-date "Sunday, 06-Nov-94 08:49:37 GMT")))
      (is (nil? (retry-wait "Sunday, 06-Nov-94 08:49:37 GMT" 1 config))))
    (testing "Retry-After soon waits the interval times the attempt"
      (is (= 10000 (retry-wait "soon" 2 config)))
      (is (= 15000 (retry-wait "Someday, 12:00" 3 config))))
    (testing "a missing Retry-After waits the interval"
      (is (= 5000 (retry-wait nil 1 config))))))

(deftest head-test
  (testing "HTTP 429 to a HEAD request sends it again"
    (with-server [too-many]
      (fn [base requests]
        (is (true? (http/exists? (str base "b.jar") {:repo-id "central"})))
        (is (= [[:head "/b.jar"] [:head "/b.jar"]] @requests))))))

(defn- with-dropping-server
  "Calls f with the base URL of a server and an atom of the connection count.
  The server closes the first drops connections without an answer."
  [drops f]
  (let [socket (java.net.ServerSocket. 0)
        connections (atom 0)
        worker (future
                 (while (not (.isClosed socket))
                   (try
                     (with-open [conn (.accept socket)]
                       (let [n (swap! connections inc)
                             in (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream conn)))]
                         (while (seq (.readLine in)))
                         (when (> n drops)
                           (let [out (.getOutputStream conn)]
                             (.write out (.getBytes "HTTP/1.1 200 OK\r\nContent-Length: 12\r\nConnection: close\r\n\r\njar contents"))
                             (.flush out)))))
                     (catch Exception _ nil))))]
    (try
      (f (str "http://localhost:" (.getLocalPort socket) "/") connections)
      (finally
        (.close socket)
        (future-cancel worker)))))

(deftest io-test
  (testing "two dropped connections then HTTP 200 returns the file"
    (with-dropping-server 2
      (fn [base connections]
        (is (= "jar contents" (http/fetch (str base "b.jar") {:repo-id "central"})))
        (is (= 3 @connections)))))
  (testing "dropped connections fail after 4 attempts"
    (with-dropping-server 100
      (fn [base connections]
        (is (thrown-with-msg? Exception #"Could not transfer"
                              (http/fetch (str base "b.jar") {:repo-id "central"})))
        (is (<= 4 @connections 8)))))
  (testing "retryHandler.count 0 with dropped connections fails after 1 attempt"
    (with-properties {"aether.connector.http.retryHandler.count" "0"}
      (fn []
        (with-dropping-server 100
          (fn [base connections]
            (is (thrown? Exception (http/fetch (str base "b.jar") {:repo-id "central"})))
            (is (<= 1 @connections 2)))))))
  (testing "a refused connection fails"
    (let [port (with-open [s (java.net.ServerSocket. 0)] (.getLocalPort s))]
      (is (thrown-with-msg? Exception #"Could not transfer"
                            (http/fetch (str "http://localhost:" port "/b.jar") {:repo-id "central"}))))))

(let [{:keys [fail error]} (t/run-tests 'retry-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
