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
      (is (instance? java.io.InputStream (:body response))))))

(require '[org.httpkit.sni-client :as sni])

(deftest alter-var-root-test
  (is (alter-var-root (var client/*default-client*) (constantly sni/default-client))))
