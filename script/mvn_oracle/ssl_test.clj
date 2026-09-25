#!/usr/bin/env bb
;; javax.net.ssl properties from CLJ_JVM_OPTS and JAVA_TOOL_OPTIONS, and macOS trust settings.
;; Run: ./bb -cp resources/src/babashka script/mvn_oracle/ssl_test.clj
(ns ssl-test
  (:require [babashka.fs :as fs]
            [babashka.impl.mvn.env :as env]
            [babashka.impl.mvn.http :as http]
            [babashka.impl.mvn.ssl :as ssl]
            [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.test :as t :refer [deftest is testing]])
  (:import [java.net ServerSocket]
           [java.security KeyStore]
           [javax.net.ssl KeyManagerFactory SSLContext SSLSocket]))

(defn- with-env* [env f]
  (with-redefs [env/getenv #(get env %)]
    (f)))

(deftest properties-test
  (testing "CLJ_JVM_OPTS overrides a -D option in JAVA_TOOL_OPTIONS"
    (with-env* {"JAVA_TOOL_OPTIONS" "-Xmx1g -Djavax.net.ssl.trustStore=/a.p12 -Djavax.net.ssl.trustStorePassword=x"
                "CLJ_JVM_OPTS" " -Djavax.net.ssl.trustStore=/b.p12  -Dfoo=bar"}
      #(is (= {"javax.net.ssl.trustStore" "/b.p12"
               "javax.net.ssl.trustStorePassword" "x"}
              (ssl/properties)))))
  (testing "no -D options give no SSL context"
    (with-env* {} #(is (nil? (ssl/ssl-context))))))

(deftest trusted-test
  (is (true? (ssl/trusted? [])))
  (is (true? (ssl/trusted? [{"kSecTrustSettingsPolicyName" "sslServer"}])))
  (is (true? (ssl/trusted? [{"kSecTrustSettingsPolicyName" "basicX509" "kSecTrustSettingsResult" 2}])))
  (is (not (ssl/trusted? [{"kSecTrustSettingsResult" 3}])))
  (is (not (ssl/trusted? [{"kSecTrustSettingsResult" 4}])))
  (is (not (ssl/trusted? [{"kSecTrustSettingsPolicyName" "eapServer" "kSecTrustSettingsResult" 1}])))
  (is (not (ssl/trusted? [{"kSecTrustSettingsPolicyName" "basicX509" "kSecTrustSettingsResult" 1}
                          {"kSecTrustSettingsPolicyName" "sslServer" "kSecTrustSettingsResult" 3}]))))

(deftest trusted-hashes-test
  (is (= #{"AA" "BB"}
         (ssl/trusted-hashes
          "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">
<plist version=\"1.0\">
<dict>
  <key>trustList</key>
  <dict>
    <key>AA</key>
    <dict><key>modDate</key><date>2025-09-25T12:12:12Z</date></dict>
    <key>BB</key>
    <dict><key>trustSettings</key>
      <array><dict><key>kSecTrustSettingsPolicyName</key><string>basicX509</string>
                   <key>kSecTrustSettingsResult</key><integer>2</integer></dict></array></dict>
    <key>CC</key>
    <dict><key>trustSettings</key>
      <array><dict><key>kSecTrustSettingsResult</key><integer>3</integer></dict></array></dict>
  </dict>
  <key>trustVersion</key>
  <integer>1</integer>
</dict>
</plist>"))))

(defn- keytool [& args]
  (apply p/shell {:out :string :err :string} "keytool" args))

(defn- serve-once
  [^ServerSocket server ^SSLContext ctx body]
  (future
    (with-open [s (doto ^SSLSocket (.createSocket (.getSocketFactory ctx) (.accept server) "localhost" 0 true)
                    (.setUseClientMode false))]
      (let [in (io/reader (.getInputStream s))]
        (loop [] (when-not (empty? (.readLine ^java.io.BufferedReader in)) (recur)))
        (doto (.getOutputStream s)
          (.write (.getBytes (str "HTTP/1.1 200 OK\r\nContent-Length: " (count body)
                                  "\r\nConnection: close\r\n\r\n" body)))
          (.flush))))))

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
            (with-env* {} #(is (thrown? Exception (http/fetch url {})))))
          (testing "a trust store in CLJ_JVM_OPTS accepts the certificate"
            (serve-once server ctx "<project/>")
            (with-env* {"CLJ_JVM_OPTS" (str "-Djavax.net.ssl.trustStore=" trust-store
                                            " -Djavax.net.ssl.trustStorePassword=changeit")}
              #(is (= "<project/>" (http/fetch url {}))))))))))

(let [{:keys [fail error]} (t/run-tests 'ssl-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
