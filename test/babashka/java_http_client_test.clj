(ns babashka.java-http-client-test
  (:require
   [babashka.test-utils :as test-utils]
   [clojure.edn :as edn]
   [clojure.test :as test :refer [deftest is]]))

(defn bb [expr]
  (edn/read-string (apply test-utils/bb nil [(str expr)])))

(deftest java-http-client-test
  (is (= [200 true]
         (bb
           '(do (ns net
                  (:import [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
                           [java.net URI]))

                (def req
                  (-> (HttpRequest/newBuilder (URI. "https://www.clojure.org"))
                      (.GET)
                      (.build)))

                (def client
                  (-> (HttpClient/newBuilder)
                      (.build)))

                (def resp (.send client req (HttpResponse$BodyHandlers/ofString)))
                [(.statusCode resp) (string? (.body resp))])))))

(deftest post-input-stream-test
  (let [body "with love from java.net.http"]
    (is (= body
           (bb
             '(do
                (ns net
                  (:require
                   [cheshire.core :as json]
                   [clojure.java.io :as io])
                  (:import
                   (java.net.http HttpClient
                                  HttpRequest
                                  HttpRequest$BodyPublishers
                                  HttpResponse$BodyHandlers)
                   (java.net URI)
                   (java.util.function Supplier)))
                (let [body "with love from java.net.http"
                      req (-> (HttpRequest/newBuilder (URI. "https://www.postman-echo.com/post"))
                              (.method "POST" (HttpRequest$BodyPublishers/ofInputStream
                                                (reify Supplier (get [_]
                                                                  (io/input-stream (.getBytes body))))))
                              (.build))
                      client (-> (HttpClient/newBuilder)
                                 (.build))
                      res (.send client req (HttpResponse$BodyHandlers/ofString))]
                  (-> (.body res)
                      (json/parse-string true)
                      :data))))))))

(deftest cookie-test
  (is (= []
         (bb '(do (ns net
                    (:import [java.net CookieManager]))
                  (-> (CookieManager.)
                      (.getCookieStore)
                      (.getCookies))))))
  (is (= "www.postman-echo.com"
         (bb '(do
                (ns net
                  (:import
                   (java.net CookieManager URI)
                   (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)))
                (let [client (-> (HttpClient/newBuilder)
                              (.cookieHandler (CookieManager.))
                              (.build))
                      req (-> (HttpRequest/newBuilder (URI. "https://www.postman-echo.com/get"))
                              (.GET)
                              (.build))]
                  (.send client req (HttpResponse$BodyHandlers/discarding))
                  (-> client
                      (.cookieHandler)
                      (.get)
                      (.getCookieStore)
                      (.getCookies)
                      first
                      (.getDomain))))))))

(deftest cert-test
  (is (= {:expired "java.security.cert.CertificateExpiredException"
          :wrong-host "sun.security.provider.certpath.SunCertPathBuilderException"
          :self-signed "sun.security.provider.certpath.SunCertPathBuilderException"}
         (bb
           '(do
              (ns net
                (:import
                 [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
                 [java.net URI]))

              (defn send-and-catch [client req handler]
                (try
                  (.send client req (HttpResponse$BodyHandlers/discarding))
                  (catch Throwable t
                    (-> (Throwable->map t) :via last :type name))))

              (let [client (-> (HttpClient/newBuilder)
                               (.build))
                    handler (HttpResponse$BodyHandlers/discarding)
                    reqs (->> [:expired :wrong-host :self-signed]
                              (map (fn [k]
                                     (let [req (-> (URI. (format "https://%s.badssl.com" (name k)))
                                                   (HttpRequest/newBuilder)
                                                   (.GET)
                                                   (.build))]
                                       [k req])))
                              (into {}))]
                (->> reqs
                     (map (fn [[k req]]
                            [k (send-and-catch client req handler)]))
                     (into {}))))))))
