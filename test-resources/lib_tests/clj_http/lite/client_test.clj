(ns clj-http.lite.client-test
  (:require [cheshire.core :as json]
            [clj-http.lite.client :as client]
            [clojure.test :as t :refer [deftest is]]))

(deftest client-test
  (is (= 200 (:status (client/get "https://www.clojure.org" {:throw-exceptions false}))))

  (is (= 200 (:status (client/get "https://postman-echo.com/get?foo1=bar1&foo2=bar2" {:throw-exceptions false}))))

  (is (= 200 (:status (client/post "https://postman-echo.com/post" {:throw-exceptions false}))))

  (is (= 200 (:status (client/post "https://postman-echo.com/post"
                                   {:body (json/generate-string {:a 1})
                                    :headers {"X-Hasura-Role" "admin"}
                                    :content-type :json
                                    :accept :json
                                    :throw-exceptions false}))))

  (is (= 200  (:status (client/put "https://postman-echo.com/put"
                                   {:body (json/generate-string {:a 1})
                                    :headers {"X-Hasura-Role" "admin"}
                                    :content-type :json
                                    :accept :json
                                    :throw-exceptions false})))))

(deftest exception-test
  (try (client/get "https://site.com/broken")
       (is false "should not reach here")
       (catch Exception e
         (is (:headers (ex-data e))))))

;; BB-TEST-PATCH: Added test
(deftest insecure-test
  (is (= 200 (:status (client/get "https://self-signed.badssl.com/" {:insecure? true})))))
