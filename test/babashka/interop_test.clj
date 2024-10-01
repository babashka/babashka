(ns babashka.interop-test
  (:require
   [babashka.test-utils :as test-utils]
   [clojure.edn :as edn]
   [clojure.test :as test :refer [deftest is testing]]))

(defn bb [input & args]
  (test-utils/normalize
   (edn/read-string
    {:readers *data-readers*
     :eof nil}
    (apply test-utils/bb (when (some? input) (str input)) (map str args)))))

(deftest vthreads-test
  (testing "can invoke methods on java.lang.VirtualThread"
    (is (= "" (bb nil "(set-agent-send-off-executor! (java.util.concurrent.Executors/newVirtualThreadPerTaskExecutor)) @(future (.getName (Thread/currentThread)))"))))
  (is (= [false true]
         (bb nil (pr-str '(do
                            (def t (Thread. (fn [])))
                            (def vt (Thread/startVirtualThread (fn [])))
                            [(.isVirtual t) (.isVirtual vt)]))))))

(deftest domain-sockets-test
  (is (= :success (bb nil (slurp "test-resources/domain_sockets.bb")))))

(deftest byte-channels-test
  (is (= :success (bb nil (slurp "test-resources/bytechannel_and_related_classes.bb")))))

(deftest proxy-inputstream-outputstream-test
  (is (= :success (bb nil (slurp "test-resources/proxy_inputstream_outputstream.bb")))))

(deftest map-entry-create-test
  (is (true? (bb nil "(= (first {1 2})
                         (clojure.lang.MapEntry. 1 2)
                         (clojure.lang.MapEntry/create 1 2))"))))

(deftest X509Certificate-test
  (is (true? (bb nil "(import java.security.cert.X509Certificate)
(import java.security.cert.CertificateFactory)
(require '[clojure.java.io :as io])
(defn x509-certificate
  ^X509Certificate
  [f]
  (let [input   (io/input-stream f)
        factory (CertificateFactory/getInstance \"X.509\")]
    (.generateCertificate factory input)))
(def cert (x509-certificate (io/file \"test-resources/certificate.crt\")))
(some? (.getSubjectX500Principal cert))
"))))

(deftest IntStream-test
  (is (pos? (bb nil "(.count (.codePoints \"woof🐕\"))"))))

(deftest Thread-sleep-test
  (is (bb nil "(Thread/sleep (int 1))
               (Thread/sleep (java.time.Duration/ofMillis 1))
               true")))

(deftest SSL-test
  (is (= :user/success
         (bb nil "(try (.createSocket (javax.net.ssl.SSLSocketFactory/getDefault) \"localhost\" 4444) (catch java.net.ConnectException e ::success))")))
  (is (bb nil " (.startHandshake (.createSocket (javax.net.ssl.SSLSocketFactory/getDefault) \"clojure.org\" 443)) ::success")))

(deftest jio-line-number-reader-test
  (is (= 2 (bb nil "(def rdr (java.io.LineNumberReader. (java.io.StringReader. \"foo\nbar\")))
                    (binding [*in* rdr] (read-line) (read-line)) (.getLineNumber rdr)"))))
