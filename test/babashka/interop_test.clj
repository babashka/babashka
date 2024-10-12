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

(deftest FI-coercion
  (is (true? (bb nil "(= [1 3] (into [] (doto (java.util.ArrayList. [1 2 3]) (.removeIf even?))))")))
  (is (true? (bb nil "(= \"abcabc\" (.computeIfAbsent (java.util.HashMap.) \"abc\" #(str % %)))")))
  (is (true? (bb nil "(= '(\\9) (-> \"a9-\" seq .stream (.filter Character/isDigit) stream-seq!))")))
  (is (true? (bb nil "(require (quote [clojure.java.io :as jio])) (import [java.io File] [java.nio.file Path Files DirectoryStream$Filter]) (pos? (count (seq (Files/newDirectoryStream (.toPath (jio/file \".\"))
                      #(-> ^Path % .toFile .isDirectory)))))")))
  (is (true? (bb nil "(import [java.util Collection] [java.util.stream Stream] [java.util.function Predicate])
                      (= '(100 100 100 100 100) (->> (Stream/generate (constantly 100)) stream-seq! (take 5)))")))
  (is (true? (bb nil "(import [java.util Collection] [java.util.stream Stream] [java.util.function Predicate])
                      (= '(1 2 3 4 5 6 7 8 9 10) (->> (Stream/iterate 1 inc) stream-seq! (take 10)))"))))

(deftest regression-test
  (is (true? (bb nil "(let [x \"f\"] (String/.startsWith \"foo\" x))"))))

(deftest clojure-1_12-interop-test
  (is (= [1 2 3] (bb nil "(map Integer/parseInt [\"1\" \"2\" \"3\"])")))
  (is (= [1 2 3] (bb nil "(map String/.length [\"1\" \"22\" \"333\"])")))
  (is (= ["1" "22" "333"] (bb nil "(map String/new [\"1\" \"22\" \"333\"])")))
  (is (= 3 (bb nil "(String/.length \"123\")")))
  (is (= "123" (bb nil "(String/new \"123\")"))))

(deftest clojure-1_12-array-test
  (is (true? (bb nil "(instance? Class long/1)"))))
