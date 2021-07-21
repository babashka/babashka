(ns vault.env-test
  (:require
    [clojure.test :refer [deftest is]]
    [vault.client.mock :refer [mock-client]]
    [vault.env :as venv]))


(deftest uri-resolution
  (let [client (mock-client {"some/path" {:id "foo"}})]
    (is (thrown? Exception (venv/resolve-uri nil "vault:some/path#id"))
        "resolution without client should throw")
    (is (thrown? Exception (venv/resolve-uri client "vault:some/path#nope"))
        "resoultion of nil secret should throw")
    (is (= "foo" (venv/resolve-uri client "vault:some/path#id")))))


(deftest env-loading
  (let [client (mock-client {"secret/foo" {:thing 123}
                             "secret/bar" {:id "abc"}})
        env {:a "12345"
             :b "vault:secret/foo#thing"
             :c "vault:secret/bar#id"}]
    (is (identical? env (venv/load! client env nil))
        "resolution without whitelisted secrets returns env unchanged")
    (is (= {:a "12345", :b 123, :c "abc"}
           (venv/load! client env #{:a :b :c}))
        "resolution allows direct passthrough")
    (is (= {:a "12345", :b 123, :c "vault:secret/bar#id"}
           (venv/load! client env #{:b}))
        "resolution does not touch non-whitelisted vars")
    (is (= {:a "12345", :b 123, :c "abc"}
           (venv/load! client env #{:b :c :d}))
        "resolution ignores missing whitelisted vars")))
