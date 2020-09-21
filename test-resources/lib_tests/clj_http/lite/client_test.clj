(ns clj-http.lite.client-test
  (:require [cheshire.core :as json]
            [clj-http.lite.client :as client]
            [clojure.test :as t :refer [deftest]]))

(deftest client-test
  (prn (:status (client/get "https://www.clojure.org" {:throw-exceptions false})))

  (prn (:status (client/get "https://postman-echo.com/get?foo1=bar1&foo2=bar2" {:throw-exceptions false})))

  (prn (:status (client/post "https://postman-echo.com/post" {:throw-exceptions false})))

  (prn (:status (client/post "https://postman-echo.com/post"
                             {:body (json/generate-string {:a 1})
                              :headers {"X-Hasura-Role" "admin"}
                              :content-type :json
                              :accept :json
                              :throw-exceptions false})))

  (prn (:status (client/put "https://postman-echo.com/put"
                            {:body (json/generate-string {:a 1})
                             :headers {"X-Hasura-Role" "admin"}
                             :content-type :json
                             :accept :json
                             :throw-exceptions false}))))
