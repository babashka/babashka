(ns hato.client-test
  (:refer-clojure :exclude [get])
  (:require [clojure.test :refer :all]
            [hato.client :refer :all]
            [clojure.java.io :as io]
            [org.httpkit.server :as http-kit]
            [cheshire.core :as json]
            [cognitect.transit :as transit]
            #_[promesa.exec :as pexec]
            #_[ring.middleware.multipart-params])
  (:import (java.io InputStream ByteArrayOutputStream)
           (java.net ProxySelector CookieHandler CookieManager)
           (java.net.http HttpClient$Redirect HttpClient$Version HttpClient)
           (java.time Duration)
           (javax.net.ssl SSLContext)
           (java.util UUID)))

(defmacro with-server
  "Spins up a local HTTP server with http-kit."
  [handler & body]
  `(let [s# (http-kit/run-server ~handler {:port 1234})]
     (try ~@body
          (finally
            (s# :timeout 100)))))

(deftest test-build-http-client
  (testing "authenticator"
    (is (.isEmpty (.authenticator (build-http-client {}))) "not set by default")
    (is (= "user" (-> (build-http-client {:authenticator {:user "user" :pass "pass"}})
                      .authenticator .get .getPasswordAuthentication .getUserName)))
    (is (.isEmpty (.authenticator (build-http-client {:authenticator :some-invalid-value}))) "ignore invalid input"))

  (testing "connect-timeout"
    (is (.isEmpty (.connectTimeout (build-http-client {}))) "not set by default")
    (is (= 5 (-> (build-http-client {:connect-timeout 5}) (.connectTimeout) ^Duration (.get) (.toMillis))))
    (is (thrown? Exception (build-http-client {:connect-timeout :not-a-number}))))

  (testing "cookie-manager and cookie-policy"
    (is (.isEmpty (.cookieHandler (build-http-client {}))) "not set by default")
    (are [x] (instance? CookieHandler (-> ^HttpClient (build-http-client {:cookie-policy x}) (.cookieHandler) (.get)))
      :none
      :all
      :original-server
      :any-random-thing                              ; Invalid values are ignored, so the default :original-server will be in effect
      )

    (let [cm (CookieManager.)]
      (is (= cm (-> (build-http-client {:cookie-handler cm :cookie-policy :all}) (.cookieHandler) (.get)))
          ":cookie-handler takes precedence over :cookie-policy")))

  (testing "redirect-policy"
    (is (= HttpClient$Redirect/NEVER (.followRedirects (build-http-client {}))) "NEVER by default")
    (are [expected option] (= expected (.followRedirects (build-http-client {:redirect-policy option})))
      HttpClient$Redirect/ALWAYS :always
      HttpClient$Redirect/NEVER :never
      HttpClient$Redirect/NORMAL :normal)
    (is (thrown? Exception (build-http-client {:redirect-policy :not-valid-value}))))

  (testing "priority"
    (is (build-http-client {:priority 1}))
    (is (build-http-client {:priority 256}))
    (is (thrown? Exception (build-http-client {:priority :not-a-number})))
    (are [x] (thrown? Exception (build-http-client {:priority x}))
      :not-a-number
      0
      257))

  (testing "proxy"
    (is (.isEmpty (.proxy (build-http-client {}))) "not set by default")
    (is (.isPresent (.proxy (build-http-client {:proxy :no-proxy}))))
    (is (.isPresent (.proxy (build-http-client {:proxy (ProxySelector/getDefault)})))))

  #_(testing "executor"
    (let [executor (pexec/fixed-pool 1)
          client (build-http-client {:executor executor})
          stored-executor (.orElse (.executor client) nil)]
      (is (instance? java.util.concurrent.ThreadPoolExecutor stored-executor) "executor has proper type")
      (is (= executor stored-executor) "executor set properly")))

  (testing "ssl-context"
    (is (= (SSLContext/getDefault) (.sslContext (build-http-client {}))))
    (is (not= (SSLContext/getDefault) (.sslContext (build-http-client {:ssl-context {:keystore         (io/resource "keystore.p12")
                                                                                     :keystore-pass    "borisman"
                                                                                     :trust-store      (io/resource "keystore.p12")
                                                                                     :trust-store-pass "borisman"}})))))

  (testing "version"
    (is (= HttpClient$Version/HTTP_2 (.version (build-http-client {}))) "HTTP_2 by default")
    (are [expected option] (= expected (.version (build-http-client {:version option})))
      HttpClient$Version/HTTP_1_1 :http-1.1
      HttpClient$Version/HTTP_2 :http-2)
    (is (thrown? Exception (build-http-client {:version :not-valid-value})))))

(deftest ^:integration test-basic-response
  (testing "basic get request returns response map"
    (let [r (get "https://httpbin.org/get")]
      (is (pos-int? (:request-time r)))
      (is (= 200 (:status r)))
      (is (= "https://httpbin.org/get" (:uri r)))
      (is (= :http-2 (:version r)))
      (is (= :get (-> r :request :request-method)))
      (is (= "gzip, deflate" (get-in r [:request :headers "accept-encoding"])))))

  #_(testing "setting executor works"
    (let [c (build-http-client {:executor (pexec/fixed-pool 1)})
          r (get "https://httpbin.org/get" {:http-client c})]
      (is (= 200 (:status r)))))

  (testing "query encoding"
    (let [r (get "https://httpbin.org/get?foo=bar<bee")]
      (is (= "https://httpbin.org/get?foo=bar%3Cbee" (:uri r)) "encodes illegals"))

    (let [r (get "https://httpbin.org/get?foo=bar%3Cbee")]
      (is (= "https://httpbin.org/get?foo=bar%3Cbee" (:uri r)) "does not double encode")))

  (testing "verbs exist"
    (are [fn] (= 200 (:status (fn "https://httpbin.org/status/200")))
      get
      post
      put
      patch
      delete
      head
      options)))

#_(deftest ^:integration test-multipart-response
  (testing "basic post request returns response map"
    (with-server (ring.middleware.multipart-params/wrap-multipart-params
                  (fn app [req]
                    {:status  200
                     :headers {"Content-Type" "application/json"}
                     :body (let [params (clojure.walk/keywordize-keys (:multipart-params req))]
                             (json/generate-string {:files {:file (-> params :file :tempfile slurp)}
                                                    :form  (select-keys params [:Content/type :eggplant :title])}))}))

      (let [uuid (.toString (UUID/randomUUID))
            _ (spit (io/file ".test-data") uuid)
            r (post "http://localhost:1234" {:as        :json
                                             :multipart [{:name "title" :content "My Awesome Picture"}
                                                         {:name "Content/type" :content "image/jpeg"}
                                                         {:name "foo.txt" :part-name "eggplant" :content "Eggplants"}
                                                         {:name "file" :content (io/file ".test-data")}]})]

        (is (= {:files {:file uuid}
                :form  {:Content/type "image/jpeg"
                        :eggplant     "Eggplants"
                        :title        "My Awesome Picture"}} (:body r)))))))

(deftest ^:integration test-basic-response-async
  (testing "basic request returns something"
    (let [r @(request {:url "https://httpbin.org/get" :async? true})]
      (is (= 200 (:status r)))))

  (testing "basic get request returns response map"
    (let [r @(get "https://httpbin.org/get" {:async? true})]
      (is (pos-int? (:request-time r)))
      (is (= 200 (:status r)))
      (is (= "https://httpbin.org/get" (:uri r)))
      (is (= :http-2 (:version r)))
      (is (= :get (-> r :request :request-method)))
      (is (= "gzip, deflate" (get-in r [:request :headers "accept-encoding"])))))

  (testing "callback"
    (is (= 200 @(get "https://httpbin.org/status/200" {:async? true} :status identity))
        "returns status on success")
    (is (= 400 @(get "https://httpbin.org/status/400" {:async? true} identity #(-> % ex-data :status)))
        "extracts response map from ex-info on fail")))

(deftest ^:integration test-exceptions
  (testing "throws on exceptional status"
    (is (thrown? Exception (get "https://httpbin.org/status/500"))))

  (testing "can opt out"
    (is (= 500 (:status (get "https://httpbin.org/status/500" {:throw-exceptions false})))))

  (testing "async"
    (is (thrown? Exception @(get "https://httpbin.org/status/400" {:async? true}))
        "default callbacks throws on error")
    (is (= 400 (:status @(get "https://httpbin.org/status/400" {:async? true :throw-exceptions? false})))
        "default callbacks does not throw if :throw-exceptions? is false")))

(deftest ^:integration test-coercions
  (testing "as default"
    (let [r (get "https://httpbin.org/get")]
      (is (string? (:body r)))))

  (testing "as byte array"
    (let [r (get "https://httpbin.org/get" {:as :byte-array})]
      (is (instance? (Class/forName "[B") (:body r)))
      (is (string? (String. ^bytes (:body r))))))

  (testing "as stream"
    (let [r (get "https://httpbin.org/get" {:as :stream})]
      (is (instance? InputStream (:body r)))
      (is (string? (-> r :body slurp)))))

  (testing "as string"
    (let [r (get "https://httpbin.org/get" {:as :string})]
      (is (string? (:body r)))))

  #_(testing "as auto"
    (let [r (get "https://httpbin.org/get" {:as :auto})]
      (is (coll? (:body r)))))

  (testing "as json"
    (let [r (get "https://httpbin.org/get" {:as :json})]
      (is (coll? (:body r)))))

  (testing "large json"
    ; Ensure large json can be parsed without the stream closing prematurely.
    (with-server (fn app [_]
                   {:status  200
                    :headers {"Content-Type" "application/json"}
                    :body    (json/generate-string (take 1000 (repeatedly (constantly {:a 1}))))})

      (let [b (:body (get "http://localhost:1234" {:as :json}))]
        (is (coll? b))
        (is (= 1000 (count b))))))

  (doseq [content-type [#_"msgpack" "json"]]
    (testing (str "decode " content-type)
      (let [body {:a [1 2]}]
        (with-server (fn app [_]
                       {:status  200
                        :headers {"Content-Type" (str "application/transit+" content-type)}
                        :body    (let [output (ByteArrayOutputStream.)]
                                   (transit/write (transit/writer output (keyword content-type)) body)
                                   (clojure.java.io/input-stream (.toByteArray output)))})

          (let [r (get "http://localhost:1234" {:as :auto})]
            (is (= body (:body r)))))))

    (testing (str "empty stream when decoding " content-type)
      (with-server (fn app [_]
                     {:status  200
                      :headers {"Content-Type" (str "application/transit+" content-type)}
                      :body    nil})

        (let [r (get "http://localhost:1234" {:as :auto})]
          (is (nil? (:body r))))))))

(deftest ^:integration test-auth
  (testing "authenticator basic auth (non-preemptive) via client option"
    (let [r (get "https://httpbin.org/basic-auth/user/pass" {:http-client {:authenticator {:user "user" :pass "pass"}}})]
      (is (= 200 (:status r))))

    (is (thrown? Exception (get "https://httpbin.org/basic-auth/user/pass" {:http-client {:authenticator {:user "user" :pass "invalid"}}}))))

  (testing "basic auth"
    (let [r (get "https://httpbin.org/basic-auth/user/pass" {:basic-auth {:user "user" :pass "pass"}})]
      (is (= 200 (:status r))))

    (let [r (get "https://user:pass@httpbin.org/basic-auth/user/pass")]
      (is (= 200 (:status r))))

    (is (thrown? Exception (get "https://httpbin.org/basic-auth/user/pass" {:basic-auth {:user "user" :pass "invalid"}})))))

(deftest ^:integration test-redirects
  ; Changed provider due to https://github.com/postmanlabs/httpbin/issues/617
  (let [redirect-to "https://httpbingo.org/get"
        uri (format "https://httpbingo.org/redirect-to?url=%s" redirect-to)]
    (testing "no redirects (default)"
      (let [r (get uri {:as :string})]
        (is (= 302 (:status r)))
        (is (= uri (:uri r)))))

    (testing "explicitly never"
      (let [r (get uri {:as :string :http-client {:redirect-policy :never}})]
        (is (= 302 (:status r)))
        (is (= uri (:uri r)))))

    (testing "always redirect"
      (let [r (get uri {:as :string :http-client {:redirect-policy :always}})]
        (is (= 200 (:status r)))
        (is (= redirect-to (:uri r)))))

    (testing "normal redirect (same protocol - accepted)"
      (let [r (get uri {:as :string :http-client {:redirect-policy :normal}})]
        (is (= 200 (:status r)))
        (is (= redirect-to (:uri r)))))

    (testing "normal redirect (different protocol - denied)"
      (let [https-tp-http-uri (format "https://httpbingo.org/redirect-to?url=%s" "http://httpbingo.org/get")
            r (get https-tp-http-uri {:as :string :http-client {:redirect-policy :normal}})]
        (is (= 302 (:status r)))
        (is (= https-tp-http-uri (:uri r)))))

    (testing "default max redirects"
      (are [status redirects] (= status (:status (get (str "https://httpbingo.org/redirect/" redirects) {:http-client {:redirect-policy :normal}})))
        200 4
        302 5))))

(deftest ^:integration test-cookies
  (testing "no cookie manager"
    (let [r (get "https://httpbin.org/cookies/set/moo/cow" {:as :json :http-client {:redirect-policy :always}})]
      (is (= 200 (:status r)))
      (is (nil? (-> r :body :cookies :moo)))))

  (testing "with cookie manager"
    (let [r (get "https://httpbin.org/cookies/set/moo/cow" {:as :json :http-client {:redirect-policy :always :cookie-policy :all}})]
      (is (= 200 (:status r)))
      (is (= "cow" (-> r :body :cookies :moo)))))

  (testing "persists over requests"
    (let [c (build-http-client {:redirect-policy :always :cookie-policy :all})
          _ (get "https://httpbin.org/cookies/set/moo/cow" {:http-client c})
          r (get "https://httpbin.org/cookies" {:as :json :http-client c})]
      (is (= 200 (:status r)))
      (is (= "cow" (-> r :body :cookies :moo))))))

(deftest ^:integration test-decompression
  (testing "gzip via byte array"
    (let [r (get "https://httpbin.org/gzip" {:as :json})]
      (is (= 200 (:status r)))
      (is (true? (-> r :body :gzipped)))))

  (testing "gzip via stream"
    (let [r (get "https://httpbin.org/gzip" {:as :stream})]
      (is (= 200 (:status r)))
      (is (instance? InputStream (:body r)))))

  (testing "deflate via byte array"
    (let [r (get "https://httpbin.org/deflate" {:as :json})]
      (is (= 200 (:status r)))
      (is (true? (-> r :body :deflated)))))

  (testing "deflate via stream"
    (let [r (get "https://httpbin.org/deflate" {:as :stream})]
      (is (= 200 (:status r)))
      (is (instance? InputStream (:body r))))))

(deftest ^:integration test-http2
  (testing "can make an http2 request"
    (let [r (get "https://httpbin.org/get" {:as :json})]
      (is (= :http-2 (:version r))))))

(deftest custom-middleware
  (let [r (get "" {:middleware [(fn [client]
                                  (fn [req]
                                    ::response))]})]
    (is (= ::response r))))

(try (get "https://httpbin.org/gzip" {:timeout 1000})
     (catch java.net.http.HttpTimeoutException _
       (doseq [v (vals (ns-publics *ns*))]
         (when (:integration (meta v))
           (println "Removing test from" v "because httpbin is slow.")
           (alter-meta! v dissoc :test)))))
