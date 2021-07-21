(ns vault.client.mock-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest testing is]]
    [vault.core :as vault])
  (:import
    (clojure.lang
      ExceptionInfo)))


(defn mock-client-authenticated
  "A mock vault client using the secrets found in the given path, defaults to `vault/client/secret-fixture-logical.edn`"
  ([path]
   (let [client (vault/new-client (str "mock:" path))]
     (vault/authenticate! client :token "fake-token")
     client))
  ([]
   (mock-client-authenticated "vault/client/secret-fixture-logical.edn")))


(deftest create-token!-test
  (testing "The return value of create-token is correct when not wrapped"
    (let [result (vault/create-token! (mock-client-authenticated) {:no-default-policy true})]
      (is (= ["root"] (:policies result)))
      (is (= false (:renewable result)))
      (is (= "" (:entity-id result)))
      (is (= ["root"] (:token-policies result)))
      (is (not (str/blank? (:accessor result))))
      (is (= 0 (:lease-duration result)))
      (is (= "service" (:token-type result)))
      (is (= false (:orphan result)))
      (is (not (str/blank? (:client-token result))))
      (is (contains? result :metadata))))
  (testing "The return value of create-token is correct when not wrapped and some options are specified"
    (let [result (vault/create-token! (mock-client-authenticated) {:policies ["hello" "goodbye"]
                                                                   :ttl "7d"})]
      (is (= ["default" "hello" "goodbye"] (:policies result)))
      (is (= false (:renewable result)))
      (is (= "" (:entity-id result)))
      (is (= ["default" "hello" "goodbye"] (:token-policies result)))
      (is (not (str/blank? (:accessor result))))
      (is (= 604800 (:lease-duration result)))
      (is (= "service" (:token-type result)))
      (is (= false (:orphan result)))
      (is (not (str/blank? (:client-token result))))
      (is (contains? result :metadata))))
  (testing "The client throws a helpful error for debugging if ttl is incorrectly formatted"
    (is (thrown-with-msg? ExceptionInfo
                          #"Mock Client doesn't recognize format of ttl"
          (vault/create-token! (mock-client-authenticated) {:ttl "BLT"}))))
  (testing "The return value of create-token is correct when not wrapped and some less common options are specified"
    (let [result (vault/create-token! (mock-client-authenticated) {:policies ["hello" "goodbye"]
                                                                   :ttl "10s"
                                                                   :no-parent true
                                                                   :no-default-policy true
                                                                   :renewable true})]
      (is (= ["hello" "goodbye"] (:policies result)))
      (is (= true (:renewable result)))
      (is (= "" (:entity-id result)))
      (is (= ["hello" "goodbye"] (:token-policies result)))
      (is (not (str/blank? (:accessor result))))
      (is (= 10 (:lease-duration result)))
      (is (= "service" (:token-type result)))
      (is (= true (:orphan result)))
      (is (not (str/blank? (:client-token result))))
      (is (contains? result :metadata))))
  (testing "The return value of create-token is correct when wrapped"
    (let [result (vault/create-token! (mock-client-authenticated) {:wrap-ttl "2h"})]
      (is (not (str/blank? (:token result))))
      (is (not (str/blank? (:accessor result))))
      (is (= 7200 (:ttl result)))
      (is (not (str/blank? (:creation-time result))))
      (is (= "auth/token/create" (:creation-path result)))
      (is (not (str/blank? (:wrapped-accessor result)))))))
