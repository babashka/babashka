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

(deftest cookie-test
  (is (= []
         (bb '(do (ns net
                    (:import [java.net CookieManager]))
                  (-> (CookieManager.)
                      (.getCookieStore)
                      (.getCookies)))))))

(deftest cert-test
  (is (= {:expired "java.security.cert.CertificateExpiredException"
          :wrong-host "sun.security.provider.certpath.SunCertPathBuilderException"
          :self-signed "sun.security.provider.certpath.SunCertPathBuilderException"}
         (bb
           '(do
              (ns net
                (:import
                 [java.net.http HttpClient HttpResponse$BodyHandlers HttpRequest]
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
