#!/usr/bin/env bb
;; A javax.net.ssl trust store given in CLJ_JVM_OPTS, against a local HTTPS server.
;; Run: ./bb -cp resources/src/babashka script/mvn_oracle/ssl_test.clj
(ns ssl-test
  (:require [babashka.classpath :as cp]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.test :as t :refer [deftest is testing]])
  (:import [java.net ServerSocket]
           [java.security KeyStore]
           [javax.net.ssl KeyManagerFactory SSLContext SSLSocket]))

(def bb (str (fs/absolutize (if (fs/windows?) "bb.exe" "bb"))))

(defn- keytool [& args]
  (apply p/shell {:out :string :err :string} "keytool" args))

(defn- serve-once [^ServerSocket server ^SSLContext ctx body]
  (future
    (with-open [s (doto ^SSLSocket (.createSocket (.getSocketFactory ctx) (.accept server) "localhost" 0 true)
                    (.setUseClientMode false))]
      (let [in (io/reader (.getInputStream s))]
        (loop [] (when-not (empty? (.readLine ^java.io.BufferedReader in)) (recur)))
        (doto (.getOutputStream s)
          (.write (.getBytes (str "HTTP/1.1 200 OK\r\nContent-Length: " (count body)
                                  "\r\nConnection: close\r\n\r\n" body)))
          (.flush))))))

(defn- fetch-in-bb [url clj-jvm-opts]
  (let [classpath (cp/get-classpath)]
    (:out (apply p/shell {:out :string :err :string :continue true
                          :extra-env {"CLJ_JVM_OPTS" clj-jvm-opts}}
                 (concat [bb] (when classpath ["-cp" classpath])
                         ["-e" (str "(require 'babashka.impl.mvn.http)"
                                    "(prn (try (babashka.impl.mvn.http/fetch \"" url "\" {})"
                                    " (catch Exception _ :failed)))")])))))

(deftest trust-store-test
  (fs/with-temp-dir [dir {}]
    (let [server-store (str (fs/path dir "server.p12"))
          cert (str (fs/path dir "server.cer"))
          trust-store (str (fs/path dir "trust.p12"))
          _ (keytool "-genkeypair" "-alias" "server" "-keyalg" "RSA" "-dname" "CN=localhost"
                     "-ext" "SAN=dns:localhost" "-validity" "1" "-storetype" "PKCS12"
                     "-keystore" server-store "-storepass" "secret")
          _ (keytool "-exportcert" "-alias" "server" "-keystore" server-store "-storepass" "secret" "-file" cert)
          _ (keytool "-importcert" "-noprompt" "-alias" "server" "-file" cert
                     "-storetype" "PKCS12" "-keystore" trust-store "-storepass" "changeit")
          ks (doto (KeyStore/getInstance "PKCS12")
               (.load (io/input-stream server-store) (char-array "secret")))
          kmf (doto (KeyManagerFactory/getInstance (KeyManagerFactory/getDefaultAlgorithm))
                (.init ks (char-array "secret")))
          ctx (doto (SSLContext/getInstance "TLS") (.init (.getKeyManagers kmf) nil nil))]
      (with-open [server (ServerSocket. 0)]
        (let [url (str "https://localhost:" (.getLocalPort server) "/a.pom")]
          (testing "the default trust store rejects the certificate"
            (serve-once server ctx "<project/>")
            (is (= ":failed\n" (fetch-in-bb url ""))))
          (testing "a trust store in CLJ_JVM_OPTS accepts the certificate"
            (serve-once server ctx "<project/>")
            (is (= "\"<project/>\"\n"
                   (fetch-in-bb url (str "-Xmx1g -Djavax.net.ssl.trustStore=" trust-store
                                         " -Djavax.net.ssl.trustStorePassword=changeit"))))))))))

(let [{:keys [fail error]} (t/run-tests 'ssl-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
