(ns babashka.curl-test
  (:require [babashka.curl :as curl]
            [cheshire.core :as json]
            [clojure.test :as t :refer [deftest]]))

(deftest curl-test
  (require '[babashka.curl :as curl] :reload-all)

  (prn (:status (curl/get "https://www.clojure.org")))

  (prn (:status (curl/get "https://postman-echo.com/get?foo1=bar1&foo2=bar2")))

  (prn (:status (curl/post "https://postman-echo.com/post")))

  (prn (:status (curl/post "https://postman-echo.com/post"
                           {:body (json/generate-string {:a 1})
                            :headers {"X-Hasura-Role" "admin"}
                            :content-type :json
                            :accept :json})))

  (prn (:status (curl/put "https://postman-echo.com/put"
                          {:body (json/generate-string {:a 1})
                           :headers {"X-Hasura-Role" "admin"}
                           :content-type :json
                           :accept :json}))))
