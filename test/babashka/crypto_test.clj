(ns babashka.crypto-test
  (:require [babashka.test-utils :as test-utils]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is]])
  (:import (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)))

(defn bb [& exprs]
  (edn/read-string (apply test-utils/bb nil (map str exprs))))

(defn hmac-sha-256 [key data]
  (let [algo "HmacSHA256"
        mac (Mac/getInstance algo)]
    (.init mac (SecretKeySpec. key algo))
    (.doFinal mac (.getBytes data "UTF-8"))))

(deftest hmac-sha-256-test
  (let [key-s "some-key"
        data "some-data"
        expected-sha (String. (.encode (java.util.Base64/getEncoder)
                                       (hmac-sha-256 (.getBytes key-s) data))
                              "utf-8")]
    (prn expected-sha)
    (is (= expected-sha (bb '(do (ns net
                                   (:import (javax.crypto Mac)
                                            (javax.crypto.spec SecretKeySpec)))
                                 (defn hmac-sha-256 [key data]
                                   (let [algo "HmacSHA256"
                                         mac (Mac/getInstance algo)]
                                     (.init mac (SecretKeySpec. key algo))
                                     (.doFinal mac (.getBytes data "UTF-8"))))
                                 (let [key-s "some-key"
                                       data "some-data"]
                                   (String. (.encode (java.util.Base64/getEncoder)
                                                     (hmac-sha-256 (.getBytes key-s) data))
                                            "utf-8"))))))))
