(ns vault.secrets.kvv2-test
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [testing deftest is]]
    [org.httpkit.client :as http]
    [vault.client.api-util :as api-util]
    [vault.client.http :as http-client]
    [vault.client.mock-test :as mock-test]
    [vault.core :as vault]
    [vault.secrets.kvv2 :as vault-kvv2])
  (:import
    (clojure.lang
      ExceptionInfo)))


(deftest list-secrets-test
  (let [path "path/passed/in"
        token-passed-in "fake-token"
        vault-url "https://vault.example.amperity.com"
        client (http-client/http-client vault-url)
        response {:auth nil
                  :data {:keys ["foo" "foo/"]}
                  :lease_duration 2764800
                  :lease_id ""
                  :renewable false}]
    (vault/authenticate! client :token token-passed-in)
    (testing "List secrets has correct response and sends correct request"
      (with-redefs
        [http/request
         (fn [req]
           (is (= :get (:method req)))
           (is (= (str vault-url "/v1/listmount/metadata/" path) (:url req)))
           (is (= token-passed-in (get (:headers req) "X-Vault-Token")))
           (is (true? (-> req :query-params :list)))
           (atom {:body response}))]
        (is (= ["foo" "foo/"]
               (vault-kvv2/list-secrets client "listmount" path)))))))


(deftest write-config!-test
  (let [mount "mount"
        token-passed-in "fake-token"
        vault-url "https://vault.example.amperity.com"
        client (http-client/http-client vault-url)
        new-config-kebab {:max-versions 5
                          :cas-required false
                          :delete-version-after "3h25m19s"}
        new-config-snake {:max_versions 5
                          :cas_required false
                          :delete_version_after "3h25m19s"}]
    (vault/authenticate! client :token token-passed-in)
    (testing "Write config sends correct request and returns true on valid call"
      (with-redefs
        [http/request
         (fn [req]
           (is (= :post (:method req)))
           (is (= (str vault-url "/v1/" mount "/config") (:url req)))
           (is (= token-passed-in (get (:headers req) "X-Vault-Token")))
           (is (= new-config-snake (:form-params req)))
           (atom {:status 204}))]
        (is (true? (vault-kvv2/write-config! client mount new-config-kebab)))))))


(deftest read-config-test
  (let [config {:max-versions 5
                :cas-required false
                :delete-version-after "3h25m19s"}
        body-str (json/generate-string {:data (api-util/snakeify-keys config)})
        mount "mount"
        token-passed-in "fake-token"
        vault-url "https://vault.example.amperity.com"
        client (http-client/http-client vault-url)]
    (vault/authenticate! client :token token-passed-in)
    (testing "Read config sends correct request and returns the config with valid call"
      (with-redefs
        [http/request
         (fn [req]
           (is (= :get (:method req)))
           (is (= (str vault-url "/v1/" mount "/config") (:url req)))
           (is (= token-passed-in (get (:headers req) "X-Vault-Token")))
           (atom {:body body-str}))]
        (is (= config (vault-kvv2/read-config client mount)))))))


(deftest read-test
  (let [lookup-response-valid-path (json/generate-string {:data {:data     {:foo "bar"}
                                                                 :metadata {:created_time  "2018-03-22T02:24:06.945319214Z"
                                                                            :deletion_time ""
                                                                            :destroyed     false
                                                                            :version       1}}})
        mount "mount"
        path-passed-in "path/passed/in"
        token-passed-in "fake-token"
        vault-url "https://vault.example.amperity.com"
        client (http-client/http-client vault-url)]
    (vault/authenticate! client :token token-passed-in)
    (testing "Read secrets sends correct request and responds correctly if secret is successfully located"
      (with-redefs
        [http/request
         (fn [req]
           (is (= :get (:method req)))
           (is (= (str vault-url "/v1/" mount "/data/" path-passed-in) (:url req)))
           (is (= token-passed-in (get (:headers req) "X-Vault-Token")))
           (atom {:body lookup-response-valid-path}))]
        (is (= {:foo "bar"} (vault-kvv2/read-secret client mount path-passed-in)))))
    (testing "Read secrets sends correct request and responds correctly if secret with version is successfully located"
      (with-redefs
        [http/request
         (fn [req]
           (is (= :get (:method req)))
           (is (= (str vault-url "/v1/" mount "/data/" path-passed-in) (:url req)))
           (is (= token-passed-in (get (:headers req) "X-Vault-Token")))
           (is (= {"version" 3} (:query-params req)))
           (atom {:body lookup-response-valid-path}))]
        (is (= {:foo "bar"} (vault-kvv2/read-secret client mount path-passed-in {:version 3 :force-read true})))))
    (testing "Read secrets sends correct request and responds correctly if no secret is found"
      (with-redefs
        [http/request
         (fn [req]
           (is (= :get (:method req)))
           (is (= (str vault-url "/v1/" mount "/data/different/path") (:url req)))
           (is (= token-passed-in (get (:headers req) "X-Vault-Token")))
           (throw (ex-info "not found" {:errors [] :status 404 :type :vault.client.api-util/api-error})))]
        (try
          (is (= {:default-val :is-here}
                 (vault-kvv2/read-secret
                   client
                   mount
                   "different/path"
                   {:not-found {:default-val :is-here}})))

          (vault-kvv2/read-secret client mount "different/path")
          (is false)
          (catch ExceptionInfo e
            (is (= {:errors nil
                    :status 404
                    :type   ::api-util/api-error}
                   (ex-data e)))))))))


(deftest write!-test
  (let [create-success {:data {:created_time  "2018-03-22T02:24:06.945319214Z"
                               :deletion_time ""
                               :destroyed     false
                               :version       1}}
        write-data {:foo "bar"
                    :zip "zap"}
        mount "mount"
        path-passed-in "path/passed/in"
        token-passed-in "fake-token"
        vault-url "https://vault.example.amperity.com"
        client (http-client/http-client vault-url)]
    (vault/authenticate! client :token token-passed-in)
    (testing "Write secrets sends correct request and returns true upon success"
      (with-redefs
        [http/request
         (fn [req]
           (is (= token-passed-in (get (:headers req) "X-Vault-Token")))
           (is (= :post (:method req)))
           (if (= (str vault-url "/v1/" mount "/metadata/" path-passed-in) (:url req))
             (do (is (= {} (:form-params req)))
                 (atom {:errors []
                        :status 200}))
             (do (is (= (str vault-url "/v1/" mount "/data/" path-passed-in) (:url req)))
                 (is (= {:data write-data}
                        (:form-params req)))
                 (atom {:body create-success
                        :status 200}))))]
        (is (= (:data create-success) (vault-kvv2/write-secret! client mount path-passed-in write-data)))))
    (testing "Write secrets sends correct request and returns false upon failure"
      (with-redefs
        [http/request
         (fn [req]
           (is (= token-passed-in (get (:headers req) "X-Vault-Token")))
           (is (= :post (:method req)))
           (if (= (str vault-url "/v1/" mount "/metadata/other-path") (:url req))
             (do (is (= {} (:form-params req)))
                 (atom {:errors []
                        :status 200}))
             (do (is (= (str vault-url "/v1/" mount "/data/other-path") (:url req)))
                 (is (= {:data write-data}
                        (:form-params req)))
                 (atom {:errors []
                        :status 500}))))]
        (is (false? (vault-kvv2/write-secret! client mount "other-path" write-data)))))))


(deftest delete-test
  (let [mount "mount"
        path-passed-in "path/passed/in"
        token-passed-in "fake-token"
        vault-url "https://vault.example.amperity.com"
        client (http-client/http-client vault-url)]
    (vault/authenticate! client :token token-passed-in)
    (testing "delete secrets send correct request and returns true upon success when no versions passed in"
      (with-redefs
        [http/request
         (fn [req]
           (is (= :delete (:method req)))
           (is (= (str vault-url "/v1/" mount "/data/" path-passed-in) (:url req)))
           (is (= token-passed-in (get (:headers req) "X-Vault-Token")))
           (atom {:status 204}))]
        (is (true? (vault-kvv2/delete-secret! client mount path-passed-in))
            (is (true? (vault-kvv2/delete-secret! client mount path-passed-in []))))
        (testing "delete secrets send correct request and returns false upon failure when no versions passed in"
          (with-redefs
            [http/request
             (fn [req]
               (is (= :delete (:method req)))
               (is (= (str vault-url "/v1/" mount "/data/" path-passed-in) (:url req)))
               (is (= token-passed-in (get (:headers req) "X-Vault-Token")))
               (atom {:status 404}))]
            (is (false? (vault-kvv2/delete-secret! client mount path-passed-in)))))
        (testing "delete secrets send correct request and returns true upon success when multiple versions passed in"
          (with-redefs
            [http/request
             (fn [req]
               (is (= :post (:method req)))
               (is (= (str vault-url "/v1/" mount "/delete/" path-passed-in) (:url req)))
               (is (= token-passed-in (get (:headers req) "X-Vault-Token")))
               (is (= {:versions [12 14 147]} (:form-params req)))
               (atom {:status 204}))]
            (is (true? (vault-kvv2/delete-secret! client mount path-passed-in [12 14 147])))))
        (testing "delete secrets send correct request and returns false upon failure when multiple versions passed in"
          (with-redefs
            [http/request
             (fn [req]
               (is (= :post (:method req)))
               (is (= (str vault-url "/v1/" mount "/delete/" path-passed-in) (:url req)))
               (is (= token-passed-in (get (:headers req) "X-Vault-Token")))
               (is (= {:versions [123]} (:form-params req)))
               (atom {:status 404}))]
            (is (false? (vault-kvv2/delete-secret! client mount path-passed-in [123])))))))))


(deftest read-metadata-test
  (let [data (json/generate-string {:data
                                    {:created_time    "2018-03-22T02:24:06.945319214Z"
                                     :current_version 3
                                     :max_versions    0
                                     :oldest_version  0
                                     :updated_time    "2018-03-22T02:36:43.986212308Z"
                                     :versions        {:1 {:created_time  "2018-03-22T02:24:06.945319214Z"
                                                           :deletion_time ""
                                                           :destroyed     false}
                                                       :2 {:created_time  "2018-03-22T02:36:33.954880664Z"
                                                           :deletion_time ""
                                                           :destroyed     false}
                                                       :3 {:created_time  "2018-03-22T02:36:43.986212308Z"
                                                           :deletion_time ""
                                                           :destroyed     false}}}})
        kebab-metadata (json/generate-string {:created-time    "2018-03-22T02:24:06.945319214Z"
                                              :current-version 3
                                              :max-versions    0
                                              :oldest-version  0
                                              :updated-time    "2018-03-22T02:36:43.986212308Z"
                                              :versions        {:1 {:created-time  "2018-03-22T02:24:06.945319214Z"
                                                                    :deletion-time ""
                                                                    :destroyed     false}
                                                                :2 {:created-time  "2018-03-22T02:36:33.954880664Z"
                                                                    :deletion-time ""
                                                                    :destroyed     false}
                                                                :3 {:created-time  "2018-03-22T02:36:43.986212308Z"
                                                                    :deletion-time ""
                                                                    :destroyed     false}}})
        mount "mount"
        path-passed-in "path/passed/in"
        token-passed-in "fake-token"
        vault-url "https://vault.example.amperity.com"
        client (http-client/http-client vault-url)]
    (vault/authenticate! client :token token-passed-in)
    (testing "Sends correct request and responds correctly upon success"
      (with-redefs
        [http/request
         (fn [req]
           (is (= :get (:method req)))
           (is (= (str vault-url "/v1/" mount "/metadata/" path-passed-in) (:url req)))
           (is (= token-passed-in (get (:headers req) "X-Vault-Token")))
           (atom {:body   data
                  :status 200}))]
        (is (= kebab-metadata (json/generate-string (vault-kvv2/read-metadata client mount path-passed-in))))))
    (testing "Sends correct request and responds correctly when metadata not found"
      (with-redefs
        [http/request
         (fn [req]
           (is (= :get (:method req)))
           (is (= (str vault-url "/v1/" mount "/metadata/" path-passed-in) (:url req)))
           (is (= token-passed-in (get (:headers req) "X-Vault-Token")))
           (throw (ex-info "not found" {:errors [] :status 404 :type :vault.client.api-util/api-error})))]
        (is (thrown? ExceptionInfo (vault-kvv2/read-metadata client mount path-passed-in {:force-read true})))
        (is (= 3 (vault-kvv2/read-metadata client mount path-passed-in {:not-found  3
                                                                        :force-read true})))))))


(deftest write-metadata-test
  (let [payload {:max-versions 5,
                 :cas-required false,
                 :delete-version-after "3h25m19s"}
        mount "mount"
        path-passed-in "path/passed/in"
        token-passed-in "fake-token"
        vault-url "https://vault.example.amperity.com"
        client (http-client/http-client vault-url)]
    (vault/authenticate! client :token token-passed-in)
    (testing "Write metadata sends correct request and responds with true upon success"
      (with-redefs
        [http/request
         (fn [req]
           (is (= :post (:method req)))
           (is (= (str vault-url "/v1/" mount "/metadata/" path-passed-in) (:url req)))
           (is (= token-passed-in (get (:headers req) "X-Vault-Token")))
           (is (= (api-util/snakeify-keys payload) (:form-params req)))
           (atom {:status 204}))]
        (is (true? (vault-kvv2/write-metadata! client mount path-passed-in payload)))))
    (testing "Write metadata sends correct request and responds with false upon failure"
      (with-redefs
        [http/request
         (fn [req]
           (is (= :post (:method req)))
           (is (= (str vault-url "/v1/" mount "/metadata/" path-passed-in) (:url req)))
           (is (= token-passed-in (get (:headers req) "X-Vault-Token")))
           (is (= (api-util/snakeify-keys payload) (:form-params req)))
           (atom {:status 500}))]
        (is (false? (vault-kvv2/write-metadata! client mount path-passed-in payload)))))))


(deftest delete-metadata-test
  (let [mount "mount"
        path-passed-in "path/passed/in"
        token-passed-in "fake-token"
        vault-url "https://vault.example.amperity.com"
        client (http-client/http-client vault-url)]
    (vault/authenticate! client :token token-passed-in)
    (testing "Sends correct request and responds correctly upon success"
      (with-redefs
        [http/request
         (fn [req]
           (is (= :delete (:method req)))
           (is (= (str vault-url "/v1/" mount "/metadata/" path-passed-in) (:url req)))
           (is (= token-passed-in (get (:headers req) "X-Vault-Token")))
           (atom {:status 204}))]
        (is (true? (vault-kvv2/delete-metadata! client mount path-passed-in)))))
    (testing "Sends correct request and responds correctly upon failure"
      (with-redefs
        [http/request
         (fn [req]
           (is (= :delete (:method req)))
           (is (= (str vault-url "/v1/" mount "/metadata/" path-passed-in) (:url req)))
           (is (= token-passed-in (get (:headers req) "X-Vault-Token")))
           (atom {:status 500}))]
        (is (false? (vault-kvv2/delete-metadata! client mount path-passed-in)))))))


(deftest destroy!-test
  (let [mount "mount"
        path-passed-in "path/passed/in"
        token-passed-in "fake-token"
        vault-url "https://vault.example.amperity.com"
        client (http-client/http-client vault-url)
        versions [1 2]]
    (vault/authenticate! client :token token-passed-in)
    (testing "Destroy secrets sends correct request and returns true upon success"
      (with-redefs
        [http/request
         (fn [req]
           (is (= :post (:method req)))
           (is (= (str vault-url "/v1/" mount "/destroy/" path-passed-in) (:url req)))
           (is (= token-passed-in (get (:headers req) "X-Vault-Token")))
           (is (= {:versions versions}
                  (:form-params req)))
           (atom {:status 204}))]
        (is (true? (vault-kvv2/destroy-secret! client mount path-passed-in versions)))))
    (testing "Destroy secrets sends correct request and returns false upon failure"
      (with-redefs
        [http/request
         (fn [req]
           (is (= :post (:method req)))
           (is (= (str vault-url "/v1/" mount "/destroy/" path-passed-in) (:url req)))
           (is (= token-passed-in (get (:headers req) "X-Vault-Token")))
           (is (= {:versions [1]}
                  (:form-params req)))
           (atom {:status 500}))]
        (is (false? (vault-kvv2/destroy-secret! client mount path-passed-in [1])))))))


(deftest undelete-secret!-test
  (let [mount "mount"
        path-passed-in "path/passed/in"
        token-passed-in "fake-token"
        vault-url "https://vault.example.amperity.com"
        client (http-client/http-client vault-url)
        versions [1 2]]
    (vault/authenticate! client :token token-passed-in)
    (testing "Undelete secrets sends correct request and returns true upon success"
      (with-redefs
        [http/request
         (fn [req]
           (is (= :post (:method req)))
           (is (= (str vault-url "/v1/" mount "/undelete/" path-passed-in) (:url req)))
           (is (= token-passed-in (get (:headers req) "X-Vault-Token")))
           (is (= {:versions versions}
                  (:form-params req)))
           (atom {:status 204}))]
        (is (true? (vault-kvv2/undelete-secret! client mount path-passed-in versions)))))
    (testing "Undelete secrets sends correct request and returns false upon failure"
      (with-redefs
        [http/request
         (fn [req]
           (is (= :post (:method req)))
           (is (= (str vault-url "/v1/" mount "/undelete/" path-passed-in) (:url req)))
           (is (= token-passed-in (get (:headers req) "X-Vault-Token")))
           (is (= {:versions [1]}
                  (:form-params req)))
           (atom {:status 500}))]
        (is (false? (vault-kvv2/undelete-secret! client mount path-passed-in [1])))))))


;; -------- Mock Client -------------------------------------------------------

(defn mock-client-kvv2
  "Creates a mock client with the data in `vault/secrets/secret-fixture-kvv2.edn`"
  []
  (mock-test/mock-client-authenticated "vault/secrets/secret-fixture-kvv2.edn"))


(deftest mock-client-test
  (testing "Mock client can correctly read values it was initialized with"
    (is (= {:batman         "Bruce Wayne"
            :captain-marvel "Carol Danvers"}
           (vault-kvv2/read-secret (mock-client-kvv2) "mount" "identities"))))
  (testing "Mock client correctly responds with a 404 to reading non-existent paths"
    (is (thrown-with-msg? ExceptionInfo #"No such secret: mount/data/hello"
          (vault-kvv2/read-secret (mock-client-kvv2) "mount" "hello")))
    (is (thrown-with-msg? ExceptionInfo #"No such secret: mount/data/identities"
          (vault-kvv2/read-secret (vault/new-client "mock:-") "mount" "identities"))))
  (testing "Mock client can write/update and read data"
    (let [client (mock-client-kvv2)]
      (is (thrown-with-msg? ExceptionInfo #"No such secret: mount/data/hello"
            (vault-kvv2/read-secret client "mount" "hello")))
      (is (true? (vault-kvv2/write-secret! client "mount" "hello" {:and-i-say "goodbye"})))
      (is (true? (vault-kvv2/write-secret! client "mount" "identities" {:intersect "Chuck"})))
      (is (= {:and-i-say "goodbye"}
             (vault-kvv2/read-secret client "mount" "hello")))
      (is (= {:intersect       "Chuck"}
             (vault-kvv2/read-secret client "mount" "identities")))))
  (testing "Mock client can write and read config"
    (let [client (mock-client-kvv2)
          config {:max-versions 5
                  :cas-required false
                  :delete-version-after "3h23m19s"}]
      (is (thrown? ExceptionInfo
            (vault-kvv2/read-config client "mount")))
      (is (true? (vault-kvv2/write-config! client "mount" config)))
      (is (= config (vault-kvv2/read-config client "mount")))))
  (testing "Mock client can write and read metadata"
    (let [client (mock-client-kvv2)]
      (is (thrown? ExceptionInfo
            (vault-kvv2/read-metadata client "mount" "doesn't exist" {:force-read true})))
      (is (= {:created-time    "2018-03-22T02:24:06.945319214Z"
              :current-version 1
              :max-versions    0
              :oldest-version  0
              :updated-time    "2018-03-22T02:36:43.986212308Z"
              :versions        {:1 {:created-time  "2018-03-22T02:24:06.945319214Z"
                                    :deletion-time ""
                                    :destroyed     false}}}
             (vault-kvv2/read-metadata client "mount" "identities" {:force-read true})))
      (is (true? (vault-kvv2/delete-metadata! client "mount" "identities")))
      (is (thrown? ExceptionInfo
            (vault-kvv2/read-metadata client "mount" "identities" {:force-read true})))
      (is (true? (vault-kvv2/write-metadata! client "mount" "hello" {:max-versions 3})))
      (is (= 3 (:max-versions (vault-kvv2/read-metadata client "mount" "hello"))))
      (is (= 5 (vault-kvv2/read-metadata client "mount" "doesn't exist" {:force-read true
                                                                         :not-found 5})))))
  (testing "Mock client returns true if path is found on delete for secret, false if not when no versions specified"
    (let [client (mock-client-kvv2)]
      (is (true? (vault-kvv2/delete-secret! client "mount" "identities")))
      (is (false? (vault-kvv2/delete-secret! client "mount" "eggsactly")))))
  (testing "Mock client always returns true on delete for secret when versions specified"
    (let [client (mock-client-kvv2)]
      (is (true? (vault-kvv2/delete-secret! client "mount" "identities" [1])))
      (is (true? (vault-kvv2/delete-secret! client "mount" "eggsactly" [4 5 6])))))
  (testing "Mock can list secrets from their associated metadata"
    (let [client (mock-client-kvv2)]
      (is (empty? (vault-kvv2/list-secrets client "hello" "yes")))
      (is (true? (vault-kvv2/write-secret! client "mount" "hello" {:and-i-say "goodbye"})))
      ;;  Paths are good enough for mock right now, but be aware they are current
      (is (= ["identities" "hello"]
             (into [] (vault-kvv2/list-secrets client "mount" ""))))))
  (testing "Mock client does not crash upon destroy"
    (is (true? (vault-kvv2/destroy-secret! (mock-client-kvv2) "mount" "identities" [1]))))
  (testing "Mock client does not crash upon undelete"
    (is (true? (vault-kvv2/undelete-secret! (mock-client-kvv2) "mount" "identities" [1])))))
