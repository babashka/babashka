(ns httpkit.client-test
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            #_:clj-kondo/ignore
            [clojure.test :refer [deftest is testing #_*report-counters*]]
            [org.httpkit.client :as client])
  (:import (clojure.lang ExceptionInfo)))

(defmethod clojure.test/report :begin-test-var [m]
  (println "===" (-> m :var meta :name))
  (println))

#_(defmethod clojure.test/report :end-test-var [_m]
  (let [{:keys [:fail :error]} @*report-counters*]
    (when (and (= "true" (System/getenv "BABASHKA_FAIL_FAST"))
               (or (pos? fail) (pos? error)))
      (println "=== Failing fast")
      (System/exit 1))))

(deftest get-test
  (is (str/includes? (:status @(client/get "https://postman-echo.com/get"))
                     "200"))
  (is (= "https://postman-echo.com/get"
         (-> @(client/get "https://postman-echo.com/get"
                          {:headers {"Accept" "application/json"}})
             :body
             (json/parse-string true)
             :url)))
  (testing "query params"
    (is (= {:foo1 "bar1", :foo2 "bar2"}
           (-> @(client/get "https://postman-echo.com/get" {:query-params {"foo1" "bar1" "foo2" "bar2"}})
               :body
               (json/parse-string true)
               :args)))))

(deftest delete-test
  (is (= 200 (:status @(client/delete "https://postman-echo.com/delete")))))

(deftest head-test
  (is (= 200 (:status @(client/head "https://postman-echo.com/head")))))

(deftest post-test
  #_(is (str/includes?
       (:body @(client/post "https://postman-echo.com/post"
                           {:body (json/generate-string {:foo "From Clojure"})}))
       "From Clojure"))
  #_(testing "file body"
    (is (str/includes?
         (:body @(client/post "https://postman-echo.com/post"
                             {:body (io/file "README.md")}))
         "babashka.curl")))
  #_(testing "JSON body"
    (let [response @(client/post "https://postman-echo.com/post"
                                {:headers {"Content-Type" "application/json"}
                                 :body (json/generate-string {:a "foo"})})
          body (:body response)
          body (json/parse-string body true)
          json (:json body)]
      (is (= {:a "foo"} json))))
  #_(testing "stream body"
    (is (str/includes?
         (:body (client/post "https://postman-echo.com/post"
                             {:body (io/input-stream "README.md")}))
         "babashka.curl")))
  #_(testing "form-params"
    (let [body (:body @(client/post "https://postman-echo.com/post"
                                   {:form-params {"name" "Michiel Borkent"}}))]
      (is (str/includes? body "Michiel Borkent"))
      (is (str/starts-with? body "{")))
    (testing "form-params from file"
      (let [tmp-file (java.io.File/createTempFile "foo" "bar")
            _ (spit tmp-file "Michiel Borkent")
            _ (.deleteOnExit tmp-file)
            body (:body (client/post "https://postman-echo.com/post"
                                     {:form-params {"file" (io/file tmp-file)
                                                    "filename" (.getPath tmp-file)}}))]
        (is (str/includes? body "foo"))
        (is (str/starts-with? body "{"))))))

;; TODO; doesn't work with httpkit client, see https://github.com/http-kit/http-kit/issues/448
#_(deftest patch-test
  (is (str/includes?
       (:body @(client/patch "https://postman-echo.com/patch"
                             {:body "hello"}))
       "hello")))

(deftest basic-auth-test
  (is (re-find #"authenticated.*true"
               (:body
                @(client/get "https://postman-echo.com/basic-auth"
                             {:basic-auth ["postman" "password"]})))))

(deftest get-response-object-test
  (let [response @(client/get "https://httpstat.us/200")]
    (is (map? response))
    (is (= 200 (:status response)))
    (is (string? (get-in response [:headers :server]))))

  (testing "response object as stream"
    (let [response @(client/get "https://httpstat.us/200" {:as :stream})]
      (is (map? response))
      (is (= 200 (:status response)))
      (is (instance? java.io.InputStream (:body response)))))

  (comment
      ;; disabled because of https://github.com/postmanlabs/httpbin/issues/617
    (testing "response object with following redirect"
      (let [response (client/get "https://httpbin.org/redirect-to?url=https://www.httpbin.org")]
        (is (map? response))
        (is (= 200 (:status response)))))

    (testing "response object without fully following redirects"
      (let [response (client/get "https://httpbin.org/redirect-to?url=https://www.httpbin.org"
                                 {:raw-args ["--max-redirs" "0"]
                                  :throw false})]
        (is (map? response))
        (is (= 302 (:status response)))
        (is (= "" (:body response)))
        (is (= "https://www.httpbin.org" (get-in response [:headers "location"])))
        (is (empty? (:redirects response)))))))

(comment
  (deftest accept-header-test
    (is (= 200
           (-> (client/get "https://httpstat.us/200"
                           {:accept :json})
               :body
               (json/parse-string true)
               :code))))

  (deftest url-encode-query-params-test
    (is (= {"my query param?" "hello there"}
           (-> (client/get "https://postman-echo.com/get" {:query-params {"my query param?" "hello there"}})
               :body
               (json/parse-string)
               (get "args")))))

  (deftest low-level-url-test
    (let [response (-> (client/request {:url {:scheme "https"
                                              :host   "httpbin.org"
                                              :port   443
                                              :path   "/get"
                                              :query  "q=test"}})
                       :body
                       (json/parse-string true))]
      (is (= {:q "test"} (:args response)))
      (is (= "httpbin.org" (get-in response [:headers :Host])))))

  (deftest download-binary-file-as-stream-test
    (testing "download image"
      (let [tmp-file (java.io.File/createTempFile "icon" ".png")]
        (.deleteOnExit tmp-file)
        (io/copy (:body (client/get "https://github.com/borkdude/babashka/raw/master/logo/icon.png" {:as :stream}))
                 tmp-file)
        (is (= (.length (io/file "test" "icon.png"))
               (.length tmp-file)))))
    (testing "download image with response headers"
      (let [tmp-file (java.io.File/createTempFile "icon" ".png")]
        (.deleteOnExit tmp-file)
        (let [resp (client/get "https://github.com/borkdude/babashka/raw/master/logo/icon.png" {:as :stream})]
          (is (= 200 (:status resp)))
          (io/copy (:body resp) tmp-file))
        (is (= (.length (io/file "test" "icon.png"))
               (.length tmp-file))))))

  (deftest stream-test
    ;; This test aims to test what is tested manually as follows:
    ;; - from https://github.com/enkot/SSE-Fake-Server: npm install sse-fake-server
    ;; - start with: PORT=1668 node fakeserver.js
    ;; - ./bb '(let [resp (client/get "http://localhost:1668/stream" {:as :stream}) body (:body resp) proc (:process resp)] (prn (take 1 (line-seq (io/reader body)))) (.destroy proc))'
    ;; ("data: Stream Hello!")
    (let [server (java.net.ServerSocket. 1668)
          port (.getLocalPort server)]
      (future (try (with-open
                    [socket (.accept server)
                     out (io/writer (.getOutputStream socket))]
                     (binding [*out* out]
                       (println "HTTP/1.1 200 OK")
                       (println "Content-Type: text/event-stream")
                       (println "Connection: keep-alive")
                       (println)
                       (loop []
                         (try (loop []
                                (println "data: Stream Hello!")
                                (Thread/sleep 20)
                                (recur))
                              (catch Exception _ nil)))))
                   (catch Exception e
                     (prn e))))
      (let [resp (client/get (str "http://localhost:" port)
                             {:as :stream})
            status (:status resp)
            headers (:headers resp)
            body (:body resp)
            proc (:process resp)]
        (is (= 200 status))
        (is (= "text/event-stream" (get headers "content-type")))
        (is (= (repeat 2 "data: Stream Hello!") (take 2 (line-seq (io/reader body)))))
        (is (= (repeat 10 "data: Stream Hello!") (take 10 (line-seq (io/reader body)))))
        (.destroy proc)))))

(comment
  (deftest command-test
    (let [resp (client/head "https://postman-echo.com/head" {:debug true})
          command (:command resp)
          opts (:options resp)]
      (is (pos? (.indexOf command "--head")))
      (is (identical? :head (:method opts)))))

  (deftest stderr-test
    (testing "should throw"
      (let [ex (is (thrown? ExceptionInfo (client/get "blah://postman-echo.com/get")))
            ex-data (ex-data ex)]
        (is (contains? ex-data :err))
        (is (str/starts-with? (:err ex-data) "curl: (1)"))
        (is (= 1 (:exit ex-data)))))
    (testing "should not throw"
      (let [resp (client/get "blah://postman-echo.com/get" {:throw false})]
        (is (contains? resp :err))
        (is (str/starts-with? (:err resp) "curl: (1)"))
        (is (= 1 (:exit resp))))))

  (deftest exceptional-status-test
    (testing "should throw"
      (let [ex (is (thrown? ExceptionInfo (client/get "https://httpstat.us/404")))
            response (ex-data ex)]
        (is (= 404 (:status response)))
        (is (zero? (:exit response)))))
    (testing "should throw when streaming based on status code"
      (let [ex (is (thrown? ExceptionInfo (client/get "https://httpstat.us/404" {:throw true
                                                                                 :as :stream})))
            response (ex-data ex)]
        (is (= 404 (:status response)))
        (is (= "404 Not Found" (slurp (:body response))))
        (is (= "" (slurp (:err response))))
        (is (delay? (:exit response)))
        (is (zero? @(:exit response)))))
    (testing "should not throw"
      (let [response (client/get "https://httpstat.us/404" {:throw false})]
        (is (= 404 (:status response)))
        (is (zero? (:exit response)))))))
