(ns babashka.java-http-client-test
  (:require
   [babashka.test-utils :as test-utils]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :as test :refer [deftest is]]
   [org.httpkit.server :as httpkit.server]))

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
  (is (= true
         (bb
           '(do
              (ns net
                (:import
                 (java.net ProxySelector)
                 (java.net.http HttpClient)))
              (let [bespoke-proxy (proxy [ProxySelector] []
                                    (connectFailed [_ _ _])
                                    (select [_ _]))
                    client (-> (HttpClient/newBuilder)
                               (.proxy bespoke-proxy)
                               (.build))]
                (= bespoke-proxy (-> (.proxy client)
                                     (.get))))))))

  (is (= 200
         (bb
           '(do
              (ns net
                (:import
                 (java.net ProxySelector
                           URI)
                 (java.net.http HttpClient
                                HttpRequest
                                HttpResponse$BodyHandlers)))
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

(deftest body-publishers-test
  (is (= true
         (bb
          '(do
             (ns net
               (:require
                [cheshire.core :as json]
                [clojure.java.io :as io]
                [clojure.string :as str])
               (:import
                (java.net URI)
                (java.net.http HttpClient
                               HttpRequest
                               HttpRequest$BodyPublishers
                               HttpResponse$BodyHandlers)
                (java.util.function Supplier)))
             (let [bp (HttpRequest$BodyPublishers/ofFile (.toPath (io/file "README.md")))
                   req (-> (HttpRequest/newBuilder (URI. "https://www.postman-echo.com/post"))
                           (.method "POST" bp)
                           (.build))
                   client (-> (HttpClient/newBuilder)
                              (.build))
                   res (.send client req (HttpResponse$BodyHandlers/ofString))
                   body-data (-> (.body res) (json/parse-string true) :data)]
                (str/includes? body-data "babashka"))))))
  (let [body "with love from java.net.http"]
    (is (= {:of-input-stream body
            :of-byte-array body
            :of-byte-arrays body}
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
                     publishers {:of-input-stream (HttpRequest$BodyPublishers/ofInputStream
                                                    (reify Supplier (get [_] (io/input-stream (.getBytes body)))))
                                 :of-byte-array (HttpRequest$BodyPublishers/ofByteArray (.getBytes body))
                                 :of-byte-arrays (HttpRequest$BodyPublishers/ofByteArrays [(.getBytes body)])}
                     client (-> (HttpClient/newBuilder)
                                (.build))
                     body-data (fn [res] (-> (.body res) (json/parse-string true) :data))]
                 (->> publishers
                      (map (fn [[k body-publisher]]
                             (let [req (-> (HttpRequest/newBuilder (URI. "https://www.postman-echo.com/post"))
                                           (.method "POST" body-publisher)
                                           (.build))]
                               [k (-> (.send client req (HttpResponse$BodyHandlers/ofString))
                                      (body-data))])))
                      (into {}))))))))
  (let [body "おはようございます！"]
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
                  (java.nio.charset Charset)
                  (java.util.function Supplier)))
               (let [body "おはようございます！"
                     req (-> (HttpRequest/newBuilder (URI. "https://www.postman-echo.com/post"))
                             (.method "POST" (HttpRequest$BodyPublishers/ofString
                                               body (Charset/forName "UTF-16")))
                             (.header "Content-Type" "text/plain; charset=utf-16")
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

(deftest body-handlers-test
  (is (= true
         (bb
          '(do
             (ns net
               (:require
                [clojure.string :as str])
               (:import
                (java.net URI)
                (java.net.http HttpClient
                               HttpRequest
                               HttpResponse$BodyHandlers)
                (java.nio.file Files StandardOpenOption)
                (java.nio.file.attribute FileAttribute)))
             (let [client (-> (HttpClient/newBuilder)
                              (.build))
                   uri (URI. "https://raw.githubusercontent.com/babashka/babashka/master/README.md")
                   req (-> (HttpRequest/newBuilder uri)
                           (.GET)
                           (.build))
                   temp-file (Files/createTempFile "bb-prefix-" "-bb-suffix" (make-array FileAttribute 0))
                   open-options (into-array StandardOpenOption [StandardOpenOption/CREATE
                                                                StandardOpenOption/WRITE])
                   handler (HttpResponse$BodyHandlers/ofFile temp-file open-options)
                   res (.send client req handler)
                   temp-file-path (str (.body res))
                   contents (slurp temp-file-path)]
               (str/includes? contents "babashka")))))))

(defn ws-handler [{:keys [init] :as opts} req]
  (when init (init req))
  (httpkit.server/as-channel
    req
    (select-keys opts [:on-close :on-ping :on-receive])))

(def ^:dynamic *ws-port* 1234)

(defmacro with-ws-server
  [opts & body]
  `(let [s# (httpkit.server/run-server (partial ws-handler ~opts) {:port ~*ws-port*})]
     (try ~@body (finally (s# :timeout 100)))))

(deftest websockets-test
  (with-ws-server {:on-receive #(httpkit.server/send! %1 %2)}
    (is (= "zomg websockets!"
           (bb
            '(do
               (ns net
                 (:require
                  [clojure.string :as str])
                 (:import
                  (java.net URI)
                  (java.net.http HttpClient
                                 WebSocket$Listener)
                  (java.util.concurrent CompletableFuture)
                  (java.util.function Function)))
               (let [p (promise)
                     uri (URI. "ws://localhost:1234")
                     listener (reify WebSocket$Listener
                                (onOpen [_ ws]
                                  (.request ws 1))
                                (onText [_ ws data last?]
                                  (.request ws 1)
                                  (.thenApply (CompletableFuture/completedFuture nil)
                                              (reify Function
                                                (apply [_ _] (deliver p (str data)))))))
                     client (HttpClient/newHttpClient)
                     ws (-> (.newWebSocketBuilder client)
                            (.buildAsync uri listener)
                            (deref))]
                 (.sendText ws "zomg websockets!" true)
                 (deref p 5000 ::timeout))))))))
