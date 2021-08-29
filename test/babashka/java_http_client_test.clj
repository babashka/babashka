(ns babashka.java-http-client-test
  (:require
   [babashka.test-utils :as test-utils]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :as test :refer [deftest is]]))

(defn bb [expr]
  (edn/read-string (apply test-utils/bb nil [(str expr)])))

(deftest java-http-client-test
  (is (= [200 true]
         (bb
          '(do (ns net
                 (:import
                  (java.net URI)
                  (java.net.http HttpClient
                                 HttpRequest
                                 HttpResponse$BodyHandlers)))

               (def req
                 (-> (HttpRequest/newBuilder (URI. "https://www.clojure.org"))
                     (.GET)
                     (.build)))

               (def client
                 (-> (HttpClient/newBuilder)
                     (.build)))

               (def resp (.send client req (HttpResponse$BodyHandlers/ofString)))
               [(.statusCode resp) (string? (.body resp))])))))

(deftest redirect-test
  (let [redirect-prog
        (fn [redirect-kind]
          (str/replace (str '(do
                               (ns net
                                 (:import
                                  (java.net.http HttpClient
                                                 HttpClient$Redirect
                                                 HttpRequest
                                                 HttpRequest$BodyPublishers
                                                 HttpResponse$BodyHandlers)
                                  (java.net URI)))
                               (defn log [x] (.println System/err x))
                               (let [req (-> (HttpRequest/newBuilder (URI. "https://www.postman-echo.com"))
                                             (.GET)
                                             (.timeout (java.time.Duration/ofSeconds 5))
                                             (.build))
                                     client (-> (HttpClient/newBuilder)
                                                (.followRedirects :redirect/kind)
                                                (.build))
                                     handler (HttpResponse$BodyHandlers/discarding)]
                                 (.statusCode (.send client req handler)))))
                       ":redirect/kind"
                       (case redirect-kind
                         :never
                         "HttpClient$Redirect/NEVER"
                         :always
                         "HttpClient$Redirect/ALWAYS")))]
    ;; TODO: make graalvm repro of never-ending request with redirect always on linux aarch64 (+ musl?)
    (when-not (and (= "aarch64" (System/getenv "BABASHKA_ARCH"))
                   (= "linux" (System/getenv "BABASHKA_PLATFORM")))
      (println "Testing redirect always")
      (is (= 200 (bb (redirect-prog :always)))))
    (println "Testing redirect never")
    (is (= 302 (bb (redirect-prog :never))))))

(deftest connect-timeout-test
  (is (= "java.net.http.HttpConnectTimeoutException"
         (bb
           '(do
              (ns net
                (:import
                 (java.net URI)
                 (java.net.http HttpClient
                                HttpRequest
                                HttpResponse$BodyHandlers)
                 (java.time Duration)))

              (let [client (-> (HttpClient/newBuilder)
                               (.connectTimeout (Duration/ofMillis 1))
                               (.build))
                    req (-> (HttpRequest/newBuilder (URI. "Https://www.postman-echo.com/get"))
                            (.GET)
                            (.build))]
                (try
                  (.send client req (HttpResponse$BodyHandlers/discarding))
                  (catch Throwable t
                    (-> (Throwable->map t)
                        :via
                        first
                        :type
                        name)))))))))

(deftest executor
  (is (= 200
         (bb
           '(do
              (ns net
                (:import
                 (java.net URI)
                 (java.net.http HttpClient
                                HttpRequest
                                HttpResponse$BodyHandlers)
                 (java.util.concurrent Executors)))
              (let [uri (URI. "https://www.postman-echo.com/get")
                    req (-> (HttpRequest/newBuilder uri)
                            (.GET)
                            (.build))
                    client (-> (HttpClient/newBuilder)
                               (.executor (Executors/newSingleThreadExecutor))
                               (.build))
                    res (.send client req (HttpResponse$BodyHandlers/discarding))]
                (.statusCode res)))))))

(deftest client-proxy
  (is (= 200
         (bb
           '(do
              (ns net
                (:import
                 (java.net ProxySelector
                           URI)
                 (java.net.http HttpClient
                                HttpRequest
                                HttpResponse$BodyHandlers)
                 (java.time Duration)))
              (let [uri (URI. "https://www.postman-echo.com/get")
                    req (-> (HttpRequest/newBuilder uri)
                            (.build))
                    client (-> (HttpClient/newBuilder)
                               (.proxy (ProxySelector/getDefault))
                               (.build))
                    res (.send client req (HttpResponse$BodyHandlers/discarding))]
                (.statusCode res)))))))

(deftest send-async-test
  (is (= 200
         (bb
           '(do
              (ns net
                (:import
                 (java.net ProxySelector
                           URI)
                 (java.net.http HttpClient
                                HttpRequest
                                HttpResponse$BodyHandlers)
                 (java.time Duration)
                 (java.util.function Function)))
              (let [client (-> (HttpClient/newBuilder)
                               (.build))
                    req (-> (HttpRequest/newBuilder (URI. "https://www.postman-echo.com/get"))
                            (.GET)
                            (.build))]
                (-> (.sendAsync client req (HttpResponse$BodyHandlers/discarding))
                    (.thenApply (reify Function (apply [_ t] (.statusCode t))))
                    (deref))))))))

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
                  (java.net URI)
                  (java.net.http HttpClient
                                 HttpRequest
                                 HttpRequest$BodyPublishers
                                 HttpResponse$BodyHandlers)
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
                   (java.net CookieManager
                             URI)
                   (java.net.http HttpClient
                                  HttpRequest
                                  HttpResponse$BodyHandlers)))
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

(deftest authenticator-test
  (is (= [401 200]
         (bb
          '(do
             (ns net
               (:import
                (java.net Authenticator
                          PasswordAuthentication
                          URI)
                (java.net.http HttpClient
                               HttpRequest
                               HttpResponse$BodyHandlers)))
             (let [no-auth-client (-> (HttpClient/newBuilder)
                                      (.build))
                   req (-> (HttpRequest/newBuilder (URI. "https://www.postman-echo.com/basic-auth"))
                           (.build))
                   handler (HttpResponse$BodyHandlers/discarding)
                   no-auth-res (.send no-auth-client req handler)
                   authenticator (proxy [Authenticator] []
                                   (getPasswordAuthentication [] (PasswordAuthentication. "postman" (char-array "password"))))
                   auth-client (-> (HttpClient/newBuilder)
                                   (.authenticator authenticator)
                                   (.build))
                   auth-res (.send auth-client req handler)]
               [(.statusCode no-auth-res) (.statusCode auth-res)]))))))

(deftest cert-test
  ;; TODO: investigate aarch64 issue
  (when-not
      (and (= "aarch64" (System/getenv "BABASHKA_ARCH"))
           (= "linux" (System/getenv "BABASHKA_PLATFORM")))
    (is (= {:expired "java.security.cert.CertificateExpiredException"
            :revoked 200 ;; TODO: fix, "sun.security.cert.CertificateRevokedException"
            :self-signed "sun.security.provider.certpath.SunCertPathBuilderException"
            :untrusted-root "sun.security.provider.certpath.SunCertPathBuilderException"
            :wrong-host "sun.security.provider.certpath.SunCertPathBuilderException"}
           (bb
            '(do
               (ns net
                 (:import
                  (java.net URI)
                  (java.net.http HttpClient
                                 HttpRequest
                                 HttpResponse$BodyHandlers)))

               (defn send-and-catch [client req handler]
                 (try
                   (let [res (.send client req (HttpResponse$BodyHandlers/discarding))]
                     (.statusCode res))
                   (catch Throwable t
                     (-> (Throwable->map t) :via last :type name))))

               (let [client (-> (HttpClient/newBuilder)
                                (.build))
                     handler (HttpResponse$BodyHandlers/discarding)
                     reqs (->> [:expired
                                :self-signed
                                :revoked
                                :untrusted-root
                                :wrong-host]
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
                      (into {})))))))))
(deftest request-timeout-test
  (is (= "java.net.http.HttpTimeoutException"
         (bb
           '(do
              (ns net
                (:import
                 (java.net URI)
                 (java.net.http HttpClient
                                HttpRequest
                                HttpResponse$BodyHandlers)
                 (java.time Duration)))

              (let [client (-> (HttpClient/newBuilder)
                               (.build))
                    req (-> (HttpRequest/newBuilder (URI. "https://www.postman-echo.com/delay/1"))
                            (.GET)
                            (.timeout (Duration/ofMillis 200))
                            (.build))]
                (try
                  (.send client req (HttpResponse$BodyHandlers/discarding))
                  (catch Throwable t
                    (-> (Throwable->map t)
                        :via
                        first
                        :type
                        name)))))))))
