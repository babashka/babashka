(ns vault.secrets.kvv1-test
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [is testing deftest]]
    [org.httpkit.client :as http]
    [vault.client.http :as http-client]
    [vault.client.mock-test :as mock-test]
    [vault.core :as vault]
    [vault.secrets.kvv1 :as vault-kvv1])
  (:import
    (clojure.lang
      ExceptionInfo)))


;; -------- HTTP Client -------------------------------------------------------

(deftest list-secrets-test
  (let [path "path/passed/in"
        token-passed-in "fake-token"
        vault-url "https://vault.example.amperity.com"
        client (http-client/http-client vault-url)
        response {:auth nil
                  :data {:keys ["foo" "foo/"]}
                  :lease_duration 2764800
                  :lease-id ""
                  :renewable false}]
    (vault/authenticate! client :token token-passed-in)
    (testing "List secrets has correct response and sends correct request"
      (with-redefs
        [http/request
         (fn [req]
           (is (= :get (:method req)))
           (is (= (str vault-url "/v1/" path) (:url req)))
           (is (= token-passed-in (get (:headers req) "X-Vault-Token")))
           (is (true? (-> req :query-params :list)))
           (atom {:body response}))]
        (is (= ["foo" "foo/"]
               (vault-kvv1/list-secrets client path)))))))


(deftest read-secret-test
  (let [lookup-response-valid-path (json/generate-string {:auth           nil
                                                          :data           {:foo "bar"
                                                                           :ttl "1h"}
                                                          :lease_duration 3600
                                                          :lease_id       ""
                                                          :renewable      false})
        path-passed-in "path/passed/in"
        path-passed-in2 "path/passed/in2"
        token-passed-in "fake-token"
        vault-url "https://vault.example.amperity.com"
        client (http-client/http-client vault-url)]
    (vault/authenticate! client :token token-passed-in)
    (testing "Read secrets sends correct request and responds correctly if secret is successfully located"
      (with-redefs
        [http/request
         (fn [req]
           (is (= :get (:method req)))
           (is (= (str vault-url "/v1/" path-passed-in) (:url req)))
           (is (= token-passed-in (get (:headers req) "X-Vault-Token")))
           (atom {:body lookup-response-valid-path}))]
        (is (= {:foo "bar" :ttl "1h"} (vault-kvv1/read-secret client path-passed-in)))))
    (testing "Read secrets sends correct request and responds correctly if no secret is found"
      (with-redefs
        [http/request
         (fn [req]
           (is (= (str vault-url "/v1/" path-passed-in2) (:url req)))
           (is (= token-passed-in (get (:headers req) "X-Vault-Token")))
           (throw (ex-info "not found" {:error [] :status 404})))]
        (try
          (vault-kvv1/read-secret client path-passed-in2)
          (catch ExceptionInfo e
            (is (= {:errors nil
                    :status 404
                    :type   :vault.client.api-util/api-error}
                   (ex-data e)))))))))


(deftest write-secret-test
  (let [create-success (json/generate-string {:data {:created_time  "2018-03-22T02:24:06.945319214Z"
                                                     :deletion_time ""
                                                     :destroyed     false
                                                     :version       1}})
        write-data {:foo "bar"
                    :zip "zap"}
        path-passed-in "path/passed/in"
        token-passed-in "fake-token"
        vault-url "https://vault.example.amperity.com"
        client (http-client/http-client vault-url)]
    (vault/authenticate! client :token token-passed-in)
    (testing "Write secrets sends correct request and returns true upon success"
      (with-redefs
        [http/request
         (fn [req]
           (is (= :post (:method req)))
           (is (= (str vault-url "/v1/" path-passed-in) (:url req)))
           (is (= token-passed-in (get (:headers req) "X-Vault-Token")))
           (is (= write-data (:form-params req)))
           (atom {:body create-success
                  :status 204}))]
        (is (true? (vault-kvv1/write-secret! client path-passed-in write-data)))))
    (testing "Write secrets sends correct request and returns false upon failure"
      (with-redefs
        [http/request
         (fn [req]
           (is (= :post (:method req)))
           (is (= (str vault-url "/v1/" path-passed-in) (:url req)))
           (is (= token-passed-in (get (:headers req) "X-Vault-Token")))
           (is (= write-data
                  (:form-params req)))
           (atom {:errors []
                  :status 400}))]
        (is (false? (vault-kvv1/write-secret! client path-passed-in write-data)))))))


(deftest delete-secret-test
  (let [path-passed-in "path/passed/in"
        token-passed-in "fake-token"
        vault-url "https://vault.example.amperity.com"
        client (http-client/http-client vault-url)]
    (vault/authenticate! client :token "fake-token")
    (testing "Delete secret returns correctly upon success, and sends correct request"
      (with-redefs
        [http/request
         (fn [req]
           (is (= :delete (:method req)))
           (is (= token-passed-in (get (:headers req) "X-Vault-Token")))
           (is (= (str vault-url "/v1/" path-passed-in) (:url req)))
           (atom {:status 204}))]
        (is (true? (vault/delete-secret! client path-passed-in)))))
    (testing "Delete secret returns correctly upon failure, and sends correct request"
      (with-redefs
        [http/request
         (fn [req]
           (is (= :delete (:method req)))
           (is (= token-passed-in (get (:headers req) "X-Vault-Token")))
           (is (= (str vault-url "/v1/" path-passed-in) (:url req)))
           (atom {:status 404}))]
        (is (false? (vault/delete-secret! client path-passed-in)))))))


;; -------- Mock Client -------------------------------------------------------

(deftest mock-client-test
  (testing "Mock client can correctly read values it was initialized with"
    (is (= {:batman         "Bruce Wayne"
            :captain-marvel "Carol Danvers"}
           (vault-kvv1/read-secret (mock-test/mock-client-authenticated) "identities"))))
  (testing "Mock client correctly responds with a 404 to non-existent paths"
    (is (thrown-with-msg? ExceptionInfo #"No such secret: hello"
          (vault-kvv1/read-secret (mock-test/mock-client-authenticated) "hello")))
    (is (thrown-with-msg? ExceptionInfo #"No such secret: identities"
          (vault-kvv1/read-secret (vault/new-client "mock:-") "identities"))))
  (testing "Mock client can write/update and read data"
    (let [client (mock-test/mock-client-authenticated)]
      (is (thrown-with-msg? ExceptionInfo #"No such secret: hello"
            (vault-kvv1/read-secret client "hello")))
      (is (true? (vault-kvv1/write-secret! client "hello" {:and-i-say "goodbye"})))
      (is (true? (vault-kvv1/write-secret! client "identities" {:intersect "Chuck"})))
      (is (= {:and-i-say "goodbye"}
             (vault-kvv1/read-secret client "hello")))
      (is (= {:intersect       "Chuck"}
             (vault-kvv1/read-secret client "identities")))))
  (testing "Mock client can list secrets"
    (let [client (mock-test/mock-client-authenticated)]
      (is (empty? (vault-kvv1/list-secrets client "hello")))
      (is (true? (vault-kvv1/write-secret! client "hello" {:and-i-say "goodbye"})))
      (is (true? (vault-kvv1/write-secret! client "identities" {:intersect "Chuck"})))
      (is (= ["identities" "hello"] (into [] (vault-kvv1/list-secrets client ""))))
      (is (= ["identities"] (into [] (vault-kvv1/list-secrets client "identities"))))))
  (testing "Mock client can delete secrets"
    (let [client (mock-test/mock-client-authenticated)]
      (is (true? (vault-kvv1/write-secret! client "hello" {:and-i-say "goodbye"})))
      (is (= {:and-i-say "goodbye"}
             (vault-kvv1/read-secret client "hello")))
      (is (= {:batman         "Bruce Wayne"
              :captain-marvel "Carol Danvers"}
             (vault-kvv1/read-secret client "identities")))
      ;; delete them
      (is (true? (vault-kvv1/delete-secret! client "hello")))
      (is (true? (vault-kvv1/delete-secret! client "identities")))
      (is (thrown? ExceptionInfo (vault-kvv1/read-secret client "hello")))
      (is (thrown? ExceptionInfo (vault-kvv1/read-secret client "identities"))))))
